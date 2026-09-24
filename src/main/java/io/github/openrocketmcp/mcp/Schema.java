package io.github.openrocketmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Small fluent builder for tool input JSON schemas.
 */
public final class Schema {
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	public static Schema object() {
		return new Schema();
	}

	public Schema str(String name, String description, boolean req) {
		return prop(name, type("string", description), req);
	}

	public Schema num(String name, String description, boolean req) {
		return prop(name, type("number", description), req);
	}

	public Schema integer(String name, String description, boolean req) {
		return prop(name, type("integer", description), req);
	}

	public Schema bool(String name, String description, boolean req) {
		return prop(name, type("boolean", description), req);
	}

	/** A physical quantity: a number in SI units, or a string with units such as "20 ft/s". */
	public Schema qty(String name, String description, boolean req) {
		JsonObject p = new JsonObject();
		JsonArray types = new JsonArray();
		types.add("number");
		types.add("string");
		p.add("type", types);
		p.addProperty("description", description + " Number = SI units, or a string with units, e.g. \"20 ft/s\".");
		return prop(name, p, req);
	}

	public Schema enumStr(String name, String description, boolean req, String... values) {
		JsonObject p = type("string", description);
		JsonArray e = new JsonArray();
		for (String v : values) {
			e.add(v);
		}
		p.add("enum", e);
		return prop(name, p, req);
	}

	public Schema array(String name, String description, JsonObject items, boolean req) {
		JsonObject p = type("array", description);
		p.add("items", items);
		return prop(name, p, req);
	}

	public Schema obj(String name, String description, boolean req) {
		JsonObject p = type("object", description);
		p.addProperty("additionalProperties", true);
		return prop(name, p, req);
	}

	public Schema prop(String name, JsonObject schema, boolean req) {
		properties.add(name, schema);
		if (req) {
			required.add(name);
		}
		return this;
	}

	public static JsonObject type(String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		if (description != null) {
			p.addProperty("description", description);
		}
		return p;
	}

	public static JsonObject quantityItem() {
		JsonObject p = new JsonObject();
		JsonArray types = new JsonArray();
		types.add("number");
		types.add("string");
		p.add("type", types);
		return p;
	}

	public JsonObject build() {
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		s.add("properties", properties);
		if (!required.isEmpty()) {
			s.add("required", required);
		}
		return s;
	}
}
