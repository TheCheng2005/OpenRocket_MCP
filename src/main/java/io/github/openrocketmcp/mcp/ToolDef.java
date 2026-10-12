package io.github.openrocketmcp.mcp;

import com.google.gson.JsonObject;

/**
 * A registered MCP tool.
 *
 * @param name        tool name
 * @param title       short human-readable title
 * @param description description shown to the model; should say when to use the tool and what it returns
 * @param inputSchema JSON schema for the arguments
 * @param readOnly    true if the tool does not modify any design
 * @param handler     implementation
 * @param meta        the tool's {@code _meta} in tools/list (e.g. an MCP Apps view: {"ui": {"resourceUri": ...}}), or null
 */
public record ToolDef(String name, String title, String description, JsonObject inputSchema, boolean readOnly,
		Handler handler, JsonObject meta) {

	public ToolDef(String name, String title, String description, JsonObject inputSchema, boolean readOnly, Handler handler) {
		this(name, title, description, inputSchema, readOnly, handler, null);
	}

	/** The same tool with another handler (wrappers keep the metadata). */
	public ToolDef withHandler(Handler h) {
		return new ToolDef(name, title, description, inputSchema, readOnly, h, meta);
	}

	/**
	 * True if only an interactive view may call it (MCP Apps {@code visibility: ["app"]}): hosts hide it from the
	 * model.
	 */
	public boolean appOnly() {
		if (meta == null || !meta.has("ui") || !meta.get("ui").isJsonObject()) {
			return false;
		}
		JsonObject ui = meta.getAsJsonObject("ui");
		if (!ui.has("visibility") || !ui.get("visibility").isJsonArray()) {
			return false;
		}
		for (var v : ui.getAsJsonArray("visibility")) {
			if ("model".equals(v.getAsString())) {
				return false;
			}
		}
		return true;
	}

	@FunctionalInterface
	public interface Handler {
		Object call(Args args) throws Exception;
	}
}
