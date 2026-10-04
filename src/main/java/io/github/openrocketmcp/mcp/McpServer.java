package io.github.openrocketmcp.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal MCP server over stdio (newline-delimited JSON-RPC 2.0). Implements tools, resources and prompts,
 * which is all the Claude clients need from a local server.
 */
public final class McpServer {

	private static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

	/**
	 * Serializes conflicting tool calls when several clients share the server (HTTP team mode): returns a lock to hold
	 * while the tool runs, or null.
	 */
	@FunctionalInterface
	public interface Guard {
		Lock lockFor(ToolDef tool, Args args);
	}

	public record Resource(String uri, String name, String description, String mimeType, Supplier<String> reader) {
	}

	public record PromptArg(String name, String description, boolean required) {
	}

	public record Prompt(String name, String description, List<PromptArg> arguments,
			Function<Map<String, String>, String> render) {
	}

	private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
			.serializeSpecialFloatingPointValues().create();
	private final Gson compact = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
	private final Map<String, ToolDef> tools = new LinkedHashMap<>();
	private final Map<String, Resource> resources = new LinkedHashMap<>();
	private final Map<String, Prompt> prompts = new LinkedHashMap<>();
	private final String name;
	private final String version;
	private final String instructions;
	private volatile Guard guard;
	private volatile UnaryOperator<String> outputFilter = UnaryOperator.identity();
	/** Tool calls in flight, by request id (as JSON text), so notifications/cancelled can reach them. */
	private final Map<String, CallContext> running = new ConcurrentHashMap<>();
	/** Marks a result that must not be sent: the client cancelled the request. */
	private static final JsonObject CANCELLED = new JsonObject();

	public McpServer(String name, String version, String instructions) {
		this.name = name;
		this.version = version;
		this.instructions = instructions;
	}

	public void guard(Guard g) {
		this.guard = g;
	}

	/** Rewrites every tool result's text (the team server shows workspace-relative paths). */
	public void outputFilter(UnaryOperator<String> f) {
		this.outputFilter = f;
	}

	public void tool(ToolDef tool) {
		if (tools.put(tool.name(), tool) != null) {
			throw new IllegalStateException("Duplicate tool " + tool.name());
		}
	}

	public void resource(Resource r) {
		resources.put(r.uri(), r);
	}

	public void prompt(Prompt p) {
		prompts.put(p.name(), p);
	}

	/** Replaces every registered tool with {@code f(tool)} (e.g. to add undo snapshots around editing tools). */
	public void wrapTools(UnaryOperator<ToolDef> f) {
		tools.replaceAll((n, t) -> f.apply(t));
	}

	public Map<String, ToolDef> tools() {
		return tools;
	}

	/**
	 * Serves requests until stdin closes. {@code out} must be the real stdout. Requests are handled one at a time in
	 * the order they arrive (a client may send several without waiting), on a worker thread, while this thread keeps
	 * reading: ping and notifications/cancelled are answered at once, so a long simulation can be cancelled and the
	 * server still answers pings. Every message written is one whole line. Returns once stdin has closed and every
	 * request has been answered.
	 */
	public void serve(InputStream in, PrintStream out) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "mcp-request");
			t.setDaemon(true);
			return t;
		});
		Consumer<JsonObject> write = msg -> {
			String text = compact.toJson(msg);
			synchronized (out) {
				out.println(text);
				out.flush();
			}
		};
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String l = line;
				String m = methodOf(l);
				if ("notifications/cancelled".equals(m) || "ping".equals(m)) {
					JsonObject response = handle(l, write); // at once: the call it cancels is still running
					if (response != null) {
						write.accept(response);
					}
					continue;
				}
				pool.submit(() -> {
					JsonObject response = handle(l, write);
					if (response != null) {
						write.accept(response);
					}
				});
			}
		} finally {
			pool.shutdown();
			try {
				pool.awaitTermination(1, TimeUnit.HOURS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** The JSON-RPC method of a message, or null (not JSON, a batch, or a response). */
	static String methodOf(String line) {
		try {
			JsonElement e = JsonParser.parseString(line);
			JsonElement m = e.isJsonObject() ? e.getAsJsonObject().get("method") : null;
			if (m != null && m.isJsonPrimitive()) {
				return m.getAsString();
			}
		} catch (RuntimeException ignored) {
			// handled (and reported) by the worker
		}
		return null;
	}

	/** Handles one JSON-RPC message; returns the response, or null for notifications. */
	public JsonObject handleLine(String line) {
		return handle(line, null);
	}

	/**
	 * Handles one JSON-RPC message; returns the response, or null for notifications and cancelled requests.
	 * {@code notify} receives progress notifications while a tool runs (null: none are sent).
	 */
	public JsonObject handle(String line, Consumer<JsonObject> notify) {
		JsonObject msg;
		try {
			msg = JsonParser.parseString(line).getAsJsonObject();
		} catch (RuntimeException e) {
			return error(null, -32700, "Parse error: " + e.getMessage());
		}
		JsonElement id = msg.get("id");
		String method = msg.has("method") ? msg.get("method").getAsString() : null;
		if (method == null) {
			return null; // a response to something we never sent; ignore
		}
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
				? msg.getAsJsonObject("params")
				: new JsonObject();
		boolean notification = id == null || id.isJsonNull();
		try {
			JsonElement result = switch (method) {
				case "initialize" -> initialize(params);
				case "ping" -> new JsonObject();
				case "tools/list" -> listTools();
				case "tools/call" -> callTool(params, id, notify);
				case "resources/list" -> listResources();
				case "resources/templates/list" -> templates();
				case "resources/read" -> readResource(params);
				case "prompts/list" -> listPrompts();
				case "prompts/get" -> getPrompt(params);
				case "notifications/cancelled" -> {
					cancel(params);
					yield null;
				}
				default -> {
					if (method.startsWith("notifications/")) {
						yield null;
					}
					throw new RpcException(-32601, "Method not found: " + method);
				}
			};
			if (notification || result == CANCELLED) {
				return null;
			}
			JsonObject response = new JsonObject();
			response.addProperty("jsonrpc", "2.0");
			response.add("id", id);
			response.add("result", result == null ? new JsonObject() : result);
			return response;
		} catch (RpcException e) {
			return notification ? null : error(id, e.code, e.getMessage());
		} catch (RuntimeException e) {
			return notification ? null : error(id, -32603, "Internal error: " + e);
		}
	}

	private JsonObject initialize(JsonObject params) {
		String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
		String version = requested != null && SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", version);
		JsonObject caps = new JsonObject();
		JsonObject toolsCap = new JsonObject();
		toolsCap.addProperty("listChanged", false);
		caps.add("tools", toolsCap);
		JsonObject resCap = new JsonObject();
		resCap.addProperty("subscribe", false);
		resCap.addProperty("listChanged", false);
		caps.add("resources", resCap);
		JsonObject promptCap = new JsonObject();
		promptCap.addProperty("listChanged", false);
		caps.add("prompts", promptCap);
		result.add("capabilities", caps);
		JsonObject info = new JsonObject();
		info.addProperty("name", name);
		info.addProperty("title", "OpenRocket MCP");
		info.addProperty("version", this.version);
		result.add("serverInfo", info);
		if (instructions != null) {
			result.addProperty("instructions", instructions);
		}
		return result;
	}

	private JsonObject listTools() {
		JsonArray arr = new JsonArray();
		for (ToolDef t : tools.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("title", t.title());
			o.addProperty("description", t.description());
			o.add("inputSchema", t.inputSchema());
			JsonObject annotations = new JsonObject();
			annotations.addProperty("title", t.title());
			annotations.addProperty("readOnlyHint", t.readOnly());
			annotations.addProperty("destructiveHint", false);
			annotations.addProperty("openWorldHint", false);
			o.add("annotations", annotations);
			arr.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("tools", arr);
		return result;
	}

	private void cancel(JsonObject params) {
		JsonElement rid = params.get("requestId");
		CallContext c = rid == null ? null : running.get(rid.toString());
		if (c != null) {
			c.cancel();
		}
	}

	private JsonObject callTool(JsonObject params, JsonElement id, Consumer<JsonObject> notify) {
		String toolName = params.has("name") ? params.get("name").getAsString() : null;
		ToolDef tool = toolName == null ? null : tools.get(toolName);
		if (tool == null) {
			throw new RpcException(-32602, "Unknown tool: " + toolName);
		}
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments")
				: new JsonObject();
		JsonElement token = params.has("_meta") && params.get("_meta").isJsonObject()
				? params.getAsJsonObject("_meta").get("progressToken")
				: null;
		CallContext ctx = new CallContext(token == null || token.isJsonNull() ? null : token, notify);
		String key = id == null || id.isJsonNull() ? null : id.toString();
		if (key != null) {
			running.put(key, ctx);
		}
		CallContext.set(ctx);
		try {
			JsonObject r = runTool(tool, toolName, new Args(arguments), ctx);
			return ctx.isCancelled() ? CANCELLED : r;
		} finally {
			CallContext.set(null);
			if (key != null) {
				running.remove(key);
			}
		}
	}

	/** Drops working keys ("_d", "_cdA": sort keys and intermediates) so they never reach the model. */
	static Object withoutInternal(Object v) {
		if (v instanceof Map<?, ?> m) {
			Map<Object, Object> out = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				if (!(e.getKey() instanceof String k && k.startsWith("_"))) {
					out.put(e.getKey(), withoutInternal(e.getValue()));
				}
			}
			return out;
		}
		if (v instanceof List<?> l) {
			List<Object> out = new ArrayList<>(l.size());
			for (Object x : l) {
				out.add(withoutInternal(x));
			}
			return out;
		}
		return v;
	}

	/**
	 * An argument the tool does not take would otherwise be ignored without a word (a filter that silently does not
	 * filter); it is refused with the tool's arguments and the closest name. Returns the error, or null.
	 */
	static String unknownArguments(String tool, JsonObject schema, JsonObject args) {
		if (schema == null || args == null || !schema.has("properties")) {
			return null;
		}
		var known = schema.getAsJsonObject("properties").keySet();
		List<String> unknown = new ArrayList<>();
		for (String k : args.keySet()) {
			// designId is harmless where a tool works on no design (models pass it out of habit).
			if (!known.contains(k) && !k.equals("designId")) {
				unknown.add(k);
			}
		}
		if (unknown.isEmpty()) {
			return null;
		}
		StringBuilder b = new StringBuilder(tool + " has no argument" + (unknown.size() > 1 ? "s " : " "));
		for (int i = 0; i < unknown.size(); i++) {
			String k = unknown.get(i), near = closest(k, known);
			b.append(i > 0 ? ", " : "").append('\'').append(k).append('\'').append(near == null ? "" : " (did you mean '" + near + "'?)");
		}
		return b.append(known.isEmpty() ? ". It takes no arguments." : ". It takes: " + String.join(", ", known) + ".").toString();
	}

	/** The known name nearest to {@code k} (edit distance up to a third of its length), or null. */
	private static String closest(String k, Iterable<String> known) {
		String best = null;
		int bestD = Math.max(2, k.length() / 3) + 1;
		for (String c : known) {
			int d = distance(k.toLowerCase(Locale.ROOT), c.toLowerCase(Locale.ROOT));
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		return best;
	}

	private static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}

	/**
	 * Checks arguments that the schema limits to a list of values, accepting any case and spaces or hyphens for
	 * underscores ("Max apogee" -> "max_apogee") and rewriting them to the listed spelling. Returns the error for a value
	 * that is not on the list, naming the choices, or null.
	 */
	static String normalizeChoices(JsonObject schema, JsonObject args) {
		if (schema == null || args == null || !schema.has("properties")) {
			return null;
		}
		JsonObject props = schema.getAsJsonObject("properties");
		for (String key : props.keySet()) {
			JsonObject p = props.getAsJsonObject(key);
			if (!p.has("enum") || !args.has(key) || !args.get(key).isJsonPrimitive()) {
				continue;
			}
			String given = args.get(key).getAsString();
			String norm = given.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
			List<String> choices = new ArrayList<>();
			String match = null;
			for (var e : p.getAsJsonArray("enum")) {
				String v = e.getAsString();
				choices.add(v);
				if (v.equals(given) || v.toLowerCase(Locale.ROOT).equals(norm)) {
					match = v;
				}
			}
			if (match == null) {
				return "'" + given + "' is not a valid " + key + "; use one of: " + String.join(", ", choices) + ".";
			}
			if (!match.equals(given)) {
				args.addProperty(key, match);
			}
		}
		return null;
	}

	private JsonObject runTool(ToolDef tool, String toolName, Args args, CallContext ctx) {
		String badChoice = unknownArguments(tool.name(), tool.inputSchema(), args.raw());
		if (badChoice == null) {
			badChoice = normalizeChoices(tool.inputSchema(), args.raw());
		}
		if (badChoice != null) {
			return textResult("Error: " + badChoice, true);
		}
		Guard g = guard;
		Lock lock = null;
		try {
			lock = g == null ? null : g.lockFor(tool, args);
		} catch (ToolException e) {
			return textResult("Error: " + e.getMessage(), true);
		}
		if (lock != null) {
			lock.lock();
		}
		try {
			ctx.checkCancelled();
			Limits.precheck(args.raw(), tool.inputSchema());
			Object value = withoutInternal(tool.handler().call(args));
			// Compact JSON: indentation roughly doubles the size of every result the model has to read.
			JsonObject r = textResult(outputFilter.apply(value instanceof String s ? s : compact.toJson(value)), false);
			for (CallContext.Image im : ctx.images()) {
				JsonObject c = new JsonObject();
				c.addProperty("type", "image");
				c.addProperty("data", im.base64());
				c.addProperty("mimeType", im.mimeType());
				r.getAsJsonArray("content").add(c);
			}
			return r;
		} catch (ToolException e) {
			return textResult(outputFilter.apply("Error: " + e.getMessage()), true);
		} catch (Exception e) {
			Log.error("Tool " + toolName + " failed", e);
			return textResult(outputFilter.apply("Error: " + unexpected(toolName, e)), true);
		} finally {
			if (lock != null) {
				lock.unlock();
			}
		}
	}

	private static JsonObject textResult(String text, boolean isError) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonArray arr = new JsonArray();
		arr.add(content);
		JsonObject result = new JsonObject();
		result.add("content", arr);
		result.addProperty("isError", isError);
		return result;
	}

	private JsonObject listResources() {
		JsonArray arr = new JsonArray();
		for (Resource r : resources.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("uri", r.uri());
			o.addProperty("name", r.name());
			o.addProperty("description", r.description());
			o.addProperty("mimeType", r.mimeType());
			arr.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("resources", arr);
		return result;
	}

	private JsonObject templates() {
		JsonObject result = new JsonObject();
		result.add("resourceTemplates", new JsonArray());
		return result;
	}

	private JsonObject readResource(JsonObject params) {
		String uri = params.has("uri") ? params.get("uri").getAsString() : null;
		Resource r = uri == null ? null : resources.get(uri);
		if (r == null) {
			throw new RpcException(-32002, "Resource not found: " + uri);
		}
		JsonObject content = new JsonObject();
		content.addProperty("uri", uri);
		content.addProperty("mimeType", r.mimeType());
		content.addProperty("text", r.reader().get());
		JsonArray arr = new JsonArray();
		arr.add(content);
		JsonObject result = new JsonObject();
		result.add("contents", arr);
		return result;
	}

	private JsonObject listPrompts() {
		JsonArray arr = new JsonArray();
		for (Prompt p : prompts.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", p.name());
			o.addProperty("description", p.description());
			JsonArray args = new JsonArray();
			for (PromptArg a : p.arguments()) {
				JsonObject ao = new JsonObject();
				ao.addProperty("name", a.name());
				ao.addProperty("description", a.description());
				ao.addProperty("required", a.required());
				args.add(ao);
			}
			o.add("arguments", args);
			arr.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("prompts", arr);
		return result;
	}

	private JsonObject getPrompt(JsonObject params) {
		String promptName = params.has("name") ? params.get("name").getAsString() : null;
		Prompt p = promptName == null ? null : prompts.get(promptName);
		if (p == null) {
			throw new RpcException(-32602, "Unknown prompt: " + promptName);
		}
		Map<String, String> args = new LinkedHashMap<>();
		if (params.has("arguments") && params.get("arguments").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : params.getAsJsonObject("arguments").entrySet()) {
				args.put(e.getKey(), e.getValue().isJsonNull() ? "" : e.getValue().getAsString());
			}
		}
		List<String> missing = new ArrayList<>();
		for (PromptArg a : p.arguments()) {
			if (a.required() && (args.get(a.name()) == null || args.get(a.name()).isBlank())) {
				missing.add(a.name());
			}
		}
		if (!missing.isEmpty()) {
			throw new RpcException(-32602, "Missing prompt arguments: " + missing);
		}
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", p.render().apply(args));
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.add("content", text);
		JsonArray messages = new JsonArray();
		messages.add(message);
		JsonObject result = new JsonObject();
		result.addProperty("description", p.description());
		result.add("messages", messages);
		return result;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("error", err);
		return response;
	}

	private static final class RpcException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		final int code;

		RpcException(int code, String message) {
			super(message);
			this.code = code;
		}
	}

	/**
	 * A sentence for an exception no tool turned into one. Malformed JSON and unparsable numbers are the caller's to
	 * fix and say so; OpenRocket's own IllegalArgumentExceptions are usually readable ("Wind level already exists for
	 * altitude: 0.0"); anything else is our bug and says to report it, with the type for the report.
	 */
	static String unexpected(String tool, Exception e) {
		String m = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("").trim();
		if (e instanceof com.google.gson.JsonParseException) {
			return "Some JSON in the arguments is not valid JSON (" + m.replaceAll("^\\S+Exception: ", "")
					.replace(" Use JsonReader.setStrictness(Strictness.LENIENT) to accept malformed JSON", "") + ").";
		}
		if (e instanceof NumberFormatException) {
			return "A value that should be a number is not: " + m.replace("For input string: ", "") + ".";
		}
		if (e instanceof IllegalArgumentException && !m.isEmpty()) {
			return m + (m.endsWith(".") ? "" : ".");
		}
		return tool + " hit an internal error (" + e.getClass().getSimpleName() + (m.isEmpty() ? "" : ": " + m)
				+ "). This is a bug in the server, not in your design: please report it with the call that caused it.";
	}
}
