package io.github.openrocketmcp.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What a running tool call can reach besides its arguments: progress reports (MCP notifications/progress, when the
 * client sent a progressToken), cancellation (notifications/cancelled), and images to show next to the text result.
 *
 * <p>Held in a thread-local while the tool runs; code outside a tool call gets an inert instance, so the analysis code
 * can report progress and check for cancellation unconditionally.
 */
public final class CallContext {
	/** An image for the result (MCP image content). */
	public record Image(String mimeType, String base64, String alt) {
	}

	/** Thrown at a cancellation checkpoint once the client has cancelled the call. */
	public static final class CancelledException extends ToolException {
		private static final long serialVersionUID = 1L;

		public CancelledException() {
			super("Cancelled.");
		}
	}

	private static final ThreadLocal<CallContext> CURRENT = new ThreadLocal<>();
	private static final CallContext INERT = new CallContext(null, null);
	/** At most this often per call, so a fast loop does not flood the client. */
	static final long MIN_INTERVAL_NANOS = 250_000_000L;

	private final JsonElement progressToken;
	private final Consumer<JsonObject> sink;
	private volatile boolean cancelled;
	private final AtomicInteger done = new AtomicInteger();
	private volatile int total;
	private volatile String unit = "simulations";
	private volatile long lastSent;
	private final List<Image> images = Collections.synchronizedList(new ArrayList<>());

	CallContext(JsonElement progressToken, Consumer<JsonObject> sink) {
		this.progressToken = progressToken;
		this.sink = sink;
	}

	/** The call running on this thread, or an inert context. */
	public static CallContext current() {
		CallContext c = CURRENT.get();
		return c == null ? INERT : c;
	}

	static void set(CallContext c) {
		if (c == null) {
			CURRENT.remove();
		} else {
			CURRENT.set(c);
		}
	}

	public boolean isCancelled() {
		return cancelled;
	}

	void cancel() {
		cancelled = true;
	}

	/** Throws {@link CancelledException} if the client cancelled this call. */
	public void checkCancelled() {
		if (cancelled) {
			throw new CancelledException();
		}
	}

	/** How many units the call will do (e.g. simulations); resets the count. 0 = unknown. */
	public void expect(int total, String unit) {
		this.total = Math.max(0, total);
		if (unit != null) {
			this.unit = unit;
		}
		done.set(0);
	}

	/** One more unit done (thread-safe); sends a progress notification at most every 250 ms, and at the end. */
	public void advance() {
		int n = done.incrementAndGet();
		int t = total;
		long now = System.nanoTime();
		if (now - lastSent >= MIN_INTERVAL_NANOS || (t > 0 && n == t)) {
			lastSent = now;
			send(n, t, t > 0 ? n + " of " + t + " " + unit : n + " " + unit + " so far");
		}
	}

	/** A progress message outside the counted units (e.g. a phase of the analysis). */
	public void report(String message) {
		send(done.get(), total, message);
	}

	private void send(int n, int t, String message) {
		if (progressToken == null || sink == null) {
			return;
		}
		JsonObject params = new JsonObject();
		params.add("progressToken", progressToken);
		params.addProperty("progress", t > 0 ? Math.min(n, t) : n);
		if (t > 0) {
			params.addProperty("total", Math.max(t, n));
		}
		params.addProperty("message", message);
		JsonObject note = new JsonObject();
		note.addProperty("jsonrpc", "2.0");
		note.addProperty("method", "notifications/progress");
		note.add("params", params);
		try {
			sink.accept(note);
		} catch (RuntimeException e) {
			// a client that went away must not fail the analysis
		}
	}

	/** Shows an image with the result (ignored outside a tool call). */
	public void attach(Image image) {
		if (this != INERT && image != null) {
			images.add(image);
		}
	}

	List<Image> images() {
		return images;
	}

	int done() {
		return done.get();
	}
}
