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
import java.util.Map;
import java.util.function.Supplier;

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

	public record Resource(String uri, String name, String description, String mimeType, Supplier<String> reader) {
	}

	public record PromptArg(String name, String description, boolean required) {
	}

	public record Prompt(String name, String description, List<PromptArg> arguments,
			java.util.function.Function<Map<String, String>, String> render) {
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

	public McpServer(String name, String version, String instructions) {
		this.name = name;
		this.version = version;
		this.instructions = instructions;
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

	public Map<String, ToolDef> tools() {
		return tools;
	}

	/** Serves requests until stdin closes. {@code out} must be the real stdout. */
	public void serve(InputStream in, PrintStream out) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isBlank()) {
				continue;
			}
			JsonObject response = handleLine(line);
			if (response != null) {
				out.println(compact.toJson(response));
				out.flush();
			}
		}
	}

	/** Handles one JSON-RPC message; returns the response, or null for notifications. */
	public JsonObject handleLine(String line) {
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
				case "tools/call" -> callTool(params);
				case "resources/list" -> listResources();
				case "resources/templates/list" -> templates();
				case "resources/read" -> readResource(params);
				case "prompts/list" -> listPrompts();
				case "prompts/get" -> getPrompt(params);
				default -> {
					if (method.startsWith("notifications/")) {
						yield null;
					}
					throw new RpcException(-32601, "Method not found: " + method);
				}
			};
			if (notification) {
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
		String version = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);
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

	private JsonObject callTool(JsonObject params) {
		String toolName = params.has("name") ? params.get("name").getAsString() : null;
		ToolDef tool = toolName == null ? null : tools.get(toolName);
		if (tool == null) {
			throw new RpcException(-32602, "Unknown tool: " + toolName);
		}
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments")
				: new JsonObject();
		try {
			Object value = tool.handler().call(new Args(arguments));
			return textResult(value instanceof String s ? s : gson.toJson(value), false);
		} catch (ToolException e) {
			return textResult("Error: " + e.getMessage(), true);
		} catch (Exception e) {
			Log.error("Tool " + toolName + " failed", e);
			String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
			return textResult("Error: " + msg, true);
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
		final int code;

		RpcException(int code, String message) {
			super(message);
			this.code = code;
		}
	}
}
