package io.github.openrocketmcp.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Typed, unit-aware access to tool arguments. All failures are {@link ToolException}s with a message the
 * model can use to correct the call.
 */
public final class Args {
	private static final Gson GSON = new Gson();
	private final JsonObject json;

	public Args(JsonObject json) {
		this.json = json == null ? new JsonObject() : json;
	}

	public JsonObject raw() {
		return json;
	}

	public boolean has(String key) {
		return json.has(key) && !json.get(key).isJsonNull();
	}

	public String str(String key) {
		if (!has(key)) {
			throw new ToolException("Missing required argument '" + key + "'.");
		}
		JsonElement e = json.get(key);
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	public String str(String key, String fallback) {
		return has(key) ? str(key) : fallback;
	}

	public double num(String key) {
		String s = str(key);
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			throw new ToolException("Argument '" + key + "' must be a number, got '" + s + "'.");
		}
	}

	public double num(String key, double fallback) {
		return has(key) ? num(key) : fallback;
	}

	public int integer(String key, int fallback) {
		return has(key) ? (int) Math.round(num(key)) : fallback;
	}

	public boolean bool(String key, boolean fallback) {
		if (!has(key)) {
			return fallback;
		}
		JsonElement e = json.get(key);
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
			return e.getAsBoolean();
		}
		return Boolean.parseBoolean(e.getAsString());
	}

	/** A physical quantity in SI. */
	public double qty(String key, Dim dim) {
		if (!has(key)) {
			throw new ToolException("Missing required argument '" + key + "' (e.g. " + Units.example(dim) + ").");
		}
		return qtyOf(json.get(key), key, dim);
	}

	public double qty(String key, Dim dim, double fallback) {
		return has(key) ? qty(key, dim) : fallback;
	}

	/** Optional quantity; NaN when absent. */
	public double qtyOrNaN(String key, Dim dim) {
		return qty(key, dim, Double.NaN);
	}

	public static double qtyOf(JsonElement e, String key, Dim dim) {
		try {
			if (e.isJsonPrimitive() && ((JsonPrimitive) e).isNumber()) {
				return e.getAsDouble();
			}
			return Units.toSi(e.getAsString(), dim);
		} catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException ex) {
			throw new ToolException("Argument '" + key + "': " + ex.getMessage());
		}
	}

	public List<Double> qtyList(String key, Dim dim) {
		List<Double> out = new ArrayList<>();
		if (!has(key)) {
			return out;
		}
		JsonElement e = json.get(key);
		if (!e.isJsonArray()) {
			throw new ToolException("Argument '" + key + "' must be an array.");
		}
		for (JsonElement item : e.getAsJsonArray()) {
			out.add(qtyOf(item, key, dim));
		}
		return out;
	}

	public List<String> strList(String key) {
		List<String> out = new ArrayList<>();
		if (!has(key)) {
			return out;
		}
		JsonElement e = json.get(key);
		if (e.isJsonArray()) {
			for (JsonElement item : e.getAsJsonArray()) {
				out.add(item.getAsString());
			}
		} else {
			for (String s : e.getAsString().split(",")) {
				if (!s.isBlank()) {
					out.add(s.trim());
				}
			}
		}
		return out;
	}

	public Args obj(String key) {
		if (!has(key)) {
			return new Args(new JsonObject());
		}
		JsonElement e = json.get(key);
		if (!e.isJsonObject()) {
			throw new ToolException("Argument '" + key + "' must be an object.");
		}
		return new Args(e.getAsJsonObject());
	}

	public List<Args> objList(String key) {
		List<Args> out = new ArrayList<>();
		if (!has(key)) {
			return out;
		}
		JsonElement e = json.get(key);
		if (!e.isJsonArray()) {
			throw new ToolException("Argument '" + key + "' must be an array of objects.");
		}
		for (JsonElement item : e.getAsJsonArray()) {
			if (!item.isJsonObject()) {
				throw new ToolException("Every element of '" + key + "' must be an object.");
			}
			out.add(new Args(item.getAsJsonObject()));
		}
		return out;
	}

	public JsonArray array(String key) {
		if (!has(key)) {
			return new JsonArray();
		}
		JsonElement e = json.get(key);
		if (!e.isJsonArray()) {
			throw new ToolException("Argument '" + key + "' must be an array.");
		}
		return e.getAsJsonArray();
	}

	public JsonElement get(String key) {
		return json.get(key);
	}

	public Iterable<String> keys() {
		return json.keySet();
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> toMap() {
		return GSON.fromJson(json, LinkedHashMap.class);
	}
}
