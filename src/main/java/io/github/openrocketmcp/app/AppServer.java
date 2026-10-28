package io.github.openrocketmcp.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.McpServer;

/**
 * The desktop app's local web server, on 127.0.0.1 only: the interface's files, and {@code POST /rpc} for tool calls
 * (JSON-RPC, the same messages the MCP server takes). Tool calls need the token that is generated for each launch and
 * handed to the page in its address, so other programs and web pages on the machine cannot drive the app.
 */
public final class AppServer {
	static final int MAX_BODY = 8 << 20;
	static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
			"css", "text/css; charset=utf-8", "svg", "image/svg+xml", "png", "image/png", "json", "application/json",
			"woff2", "font/woff2", "ico", "image/x-icon", "map", "application/json");

	private final McpServer server;
	private final Path ui;
	private final String token;
	private final List<String> extraOrigins;
	private HttpServer http;
	private String origin;
	private final AtomicLong lastSeen = new AtomicLong(0);

	/** {@code ui}: folder of the built interface (null: the copy inside the jar, if any). */
	public AppServer(McpServer server, Path ui, List<String> extraOrigins) {
		this.server = server;
		this.ui = ui;
		this.extraOrigins = extraOrigins;
		byte[] b = new byte[24];
		new SecureRandom().nextBytes(b);
		this.token = HexFormat.of().formatHex(b);
	}

	public String token() {
		return token;
	}

	/** Milliseconds since epoch of the page's last request (0: never). */
	public long lastSeen() {
		return lastSeen.get();
	}

	public InetSocketAddress start(int port) throws IOException {
		http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		http.createContext("/", this::handle);
		http.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
		http.start();
		InetSocketAddress a = http.getAddress();
		origin = "http://127.0.0.1:" + a.getPort();
		return a;
	}

	public String url() {
		return origin + "/#token=" + token;
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
		}
	}

	private void handle(HttpExchange ex) throws IOException {
		try (ex) {
			try {
				route(ex);
			} catch (RuntimeException | LinkageError e) {
				Log.error("App request failed", e);
				try {
					send(ex, 500, "text/plain", "Internal error".getBytes(StandardCharsets.UTF_8));
				} catch (IOException | RuntimeException ignored) {
					// the response had already started
				}
			}
		}
	}

	private void route(HttpExchange ex) throws IOException {
		var h = ex.getResponseHeaders();
		h.add("X-Content-Type-Options", "nosniff");
		h.add("Referrer-Policy", "no-referrer");
		String path = ex.getRequestURI().getRawPath();
		if (path.equals("/rpc")) {
			rpc(ex);
			return;
		}
		if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
			send(ex, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (path.equals("/health")) {
			send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
			return;
		}
		file(ex, path);
	}

	private boolean originOk(HttpExchange ex) {
		String o = ex.getRequestHeaders().getFirst("Origin");
		if (o == null) {
			return true; // not a browser page making a cross-site call
		}
		return o.equals(origin) || o.equals(origin.replace("127.0.0.1", "localhost")) || extraOrigins.contains(o);
	}

	private boolean tokenOk(HttpExchange ex) {
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			return false;
		}
		return MessageDigest.isEqual(auth.substring(7).trim().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
	}

	private void rpc(HttpExchange ex) throws IOException {
		if (!ex.getRequestMethod().equals("POST")) {
			send(ex, 405, "text/plain", "Use POST".getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (!originOk(ex)) {
			send(ex, 403, "text/plain", "Origin not allowed".getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (!tokenOk(ex)) {
			send(ex, 401, "text/plain", "Missing or wrong token".getBytes(StandardCharsets.UTF_8));
			return;
		}
		lastSeen.set(System.currentTimeMillis());
		byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
		if (body.length > MAX_BODY) {
			send(ex, 413, "text/plain", "Too large".getBytes(StandardCharsets.UTF_8));
			return;
		}
		JsonObject r = server.handleLine(new String(body, StandardCharsets.UTF_8));
		send(ex, 200, "application/json", (r == null ? "{}" : r.toString()).getBytes(StandardCharsets.UTF_8));
	}

	private void file(HttpExchange ex, String path) throws IOException {
		String rel = path.equals("/") ? "index.html" : path.substring(1);
		if (rel.contains("..") || rel.contains("\\") || rel.contains("%")) {
			send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
			return;
		}
		byte[] bytes = null;
		if (ui != null) {
			Path f = ui.resolve(rel).normalize();
			if (f.startsWith(ui) && Files.isRegularFile(f)) {
				bytes = Files.readAllBytes(f);
			}
		} else {
			try (InputStream in = AppServer.class.getResourceAsStream("/rocketmaxxing/" + rel)) {
				if (in != null) {
					bytes = in.readAllBytes();
				}
			}
		}
		if (bytes == null) {
			send(ex, 404, "text/plain", ("Not found" + (ui == null && rel.equals("index.html")
					? ": the interface is not built. Run ./gradlew rocketmaxxing" : "")).getBytes(StandardCharsets.UTF_8));
			return;
		}
		String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		// Hashed asset names never change: cache them; the page itself always comes fresh.
		ex.getResponseHeaders().add("Cache-Control", rel.startsWith("assets/") ? "max-age=31536000, immutable" : "no-store");
		if (rel.equals("index.html")) {
			ex.getResponseHeaders().add("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; "
					+ "style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'");
		}
		send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), bytes);
	}

	private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
		ex.getResponseHeaders().add("Content-Type", type);
		boolean head = ex.getRequestMethod().equals("HEAD");
		ex.sendResponseHeaders(code, head ? -1 : body.length);
		if (!head) {
			ex.getResponseBody().write(body);
		}
	}
}
