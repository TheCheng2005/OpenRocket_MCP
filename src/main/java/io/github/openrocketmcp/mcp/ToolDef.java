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
 */
public record ToolDef(String name, String title, String description, JsonObject inputSchema, boolean readOnly,
		Handler handler) {

	@FunctionalInterface
	public interface Handler {
		Object call(Args args) throws Exception;
	}
}
