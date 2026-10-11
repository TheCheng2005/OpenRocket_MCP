package io.github.openrocketmcp.or;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.openrocketmcp.mcp.ToolException;

/**
 * Fetches product pages for add_from_link. Public web addresses only: a link to the server's own machine or the
 * team's private network (localhost, 10.x, 192.168.x, link-local...) is refused, also after a redirect, so a team
 * server cannot be used to read internal pages. Pages are capped at 3 MB and 20 s.
 */
public final class Web {
	private Web() {
	}

	/** How pages are fetched; tests replace it. */
	public interface Client {
		Response get(URI uri) throws IOException;

		Response postJson(URI uri, String json) throws IOException;
	}

	/** Status, Location header (for redirects) and body. */
	public record Response(int status, String location, String body) {
	}

	private static volatile Client client = new Real();

	/** For tests: fetch through {@code c} (null restores the network). */
	public static void use(Client c) {
		client = c == null ? new Real() : c;
	}

	static final int MAX_BYTES = 3 * 1024 * 1024;

	/** GET a public page, following up to five redirects, each one checked. */
	public static String get(String url) {
		URI u = check(url);
		try {
			for (int hop = 0; hop < 6; hop++) {
				Response r = client.get(u);
				if (r.status() >= 300 && r.status() < 400 && r.location() != null) {
					u = check(u.resolve(r.location()).toString());
					continue;
				}
				return body(u, r);
			}
			throw new ToolException("Too many redirects from " + url + ".");
		} catch (IOException e) {
			throw unreachable(u, e);
		}
	}

	/** POST JSON to a public API (ThrustCurve.org). */
	public static String postJson(String url, String json) {
		URI u = check(url);
		try {
			return body(u, client.postJson(u, json));
		} catch (IOException e) {
			throw unreachable(u, e);
		}
	}

	private static String body(URI u, Response r) {
		if (r.status() == 403 || r.status() == 429 || r.status() == 503) {
			throw new ToolException(u.getHost() + " refused the request (HTTP " + r.status() + "; many shops block automated "
					+ "access). Open the page, select all, copy, and pass the text as pageText instead.");
		}
		if (r.status() != 200) {
			throw new ToolException(u + " returned HTTP " + r.status() + ".");
		}
		return r.body();
	}

	private static ToolException unreachable(URI u, IOException e) {
		return new ToolException("Could not read " + u.getHost() + " (" + e.getMessage() + "). Without internet access from this "
				+ "server, open the page, select all, copy, and pass the text as pageText.");
	}

	/** The URL, if it is http(s) to a public address. */
	static URI check(String url) {
		URI u;
		try {
			u = URI.create(url.trim());
		} catch (IllegalArgumentException e) {
			throw new ToolException("Not a web address: " + url);
		}
		String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https") || u.getHost() == null) {
			throw new ToolException("Give an http(s) link to the product page, e.g. https://shop.example.com/parachute-84 (got " + url + ").");
		}
		String host = u.getHost().toLowerCase(Locale.ROOT);
		if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
			throw new ToolException("Links to " + host + " are not fetched: only public product pages.");
		}
		try {
			for (InetAddress a : InetAddress.getAllByName(host)) {
				if (isPrivate(a)) {
					throw new ToolException("Links to " + host + " (" + a.getHostAddress() + ", a private or local address) are not "
							+ "fetched: only public product pages.");
				}
			}
		} catch (UnknownHostException e) {
			// Behind a proxy the name may only resolve there; the proxy then decides. A literal private IP never gets here.
		}
		return u;
	}

	static boolean isPrivate(InetAddress a) {
		byte[] b = a.getAddress();
		boolean cgnat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
		boolean ula = b.length == 16 && (b[0] & 0xfe) == 0xfc; // fc00::/7
		return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
				|| a.isMulticastAddress() || cgnat || ula;
	}

	/** The network. Redirects are returned, not followed, so every hop is checked. */
	private static final class Real implements Client {
		private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NEVER).build();

		@Override
		public Response get(URI uri) throws IOException {
			return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", AGENT)
					.header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8").GET().build());
		}

		@Override
		public Response postJson(URI uri, String json) throws IOException {
			return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", AGENT)
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
		}

		private Response send(HttpRequest req) throws IOException {
			try {
				HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				try (InputStream in = r.body()) {
					byte[] buf = new byte[65536];
					for (int n; (n = in.read(buf)) > 0;) {
						out.write(buf, 0, n);
						if (out.size() > MAX_BYTES) {
							throw new IOException("the page is larger than 3 MB");
						}
					}
				}
				Charset cs = charset(r.headers().firstValue("Content-Type").orElse(""));
				return new Response(r.statusCode(), r.headers().firstValue("Location").orElse(null), out.toString(cs));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted");
			}
		}

		private static Charset charset(String contentType) {
			Matcher m = Pattern.compile("(?i)charset=([\\w-]+)").matcher(contentType);
			try {
				return m.find() ? Charset.forName(m.group(1)) : StandardCharsets.UTF_8;
			} catch (RuntimeException e) {
				return StandardCharsets.UTF_8;
			}
		}

		private static final String AGENT = "Mozilla/5.0 (compatible; openrocket-mcp; +https://github.com/TheCheng2005/OpenRocket_MCP)";
	}
}
