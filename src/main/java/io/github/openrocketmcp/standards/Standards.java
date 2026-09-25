package io.github.openrocketmcp.standards;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.UnitSystem;
import io.github.openrocketmcp.units.Units;

/**
 * Team standards (safety factors, pin ratings, packing factors, launch site) merged over the built-in
 * defaults, plus the competition rule set they reference.
 */
public final class Standards {
	public static final String FILE_NAME = "openrocket-mcp.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

	private final JsonObject data;
	private final JsonObject rules;
	private final Path source;

	private Standards(JsonObject data, JsonObject rules, Path source) {
		this.data = data;
		this.rules = rules;
		this.source = source;
	}

	public static Standards defaults() {
		return build(new JsonObject(), null);
	}

	/**
	 * Loads the team file: an explicit path, else $OPENROCKET_MCP_STANDARDS, else ./openrocket-mcp.json, else
	 * built-in defaults only.
	 */
	public static Standards discover() {
		String env = System.getenv("OPENROCKET_MCP_STANDARDS");
		if (env != null && !env.isBlank()) {
			return load(Path.of(env));
		}
		Path local = Path.of(FILE_NAME);
		if (Files.exists(local)) {
			return load(local);
		}
		return defaults();
	}

	public static Standards load(Path path) {
		Path p = path.toAbsolutePath().normalize();
		try {
			JsonObject team = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
			return build(team, p);
		} catch (IOException e) {
			throw new ToolException("Cannot read standards file " + p + ": " + e.getMessage());
		} catch (RuntimeException e) {
			throw new ToolException("Invalid JSON in " + p + ": " + e.getMessage());
		}
	}

	private static Standards build(JsonObject team, Path source) {
		JsonObject merged = resource("/openrocketmcp/default-standards.json");
		deepMerge(merged, team);
		JsonObject rules = loadRules(merged.has("ruleset") ? merged.get("ruleset").getAsString() : null, source);
		Standards s = new Standards(merged, rules, source);
		if (merged.has("units")) {
			Units.setSystem(UnitSystem.parse(merged.get("units").getAsString()));
		}
		return s;
	}

	private static JsonObject loadRules(String ruleset, Path standardsFile) {
		if (ruleset == null || ruleset.isBlank() || ruleset.equals("none")) {
			return new JsonObject();
		}
		if (ruleset.endsWith(".json")) {
			Path p = standardsFile != null && standardsFile.getParent() != null
					? standardsFile.getParent().resolve(ruleset) : Path.of(ruleset);
			try {
				return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
			} catch (IOException e) {
				throw new ToolException("Cannot read rule set " + p + ": " + e.getMessage());
			}
		}
		JsonObject r = resource("/openrocketmcp/rules/" + ruleset + ".json");
		if (r == null) {
			throw new ToolException("Unknown rule set '" + ruleset + "'. Built in: launch-canada-2027, launch-canada-r4, none, or a path to a .json file.");
		}
		return r;
	}

	private static JsonObject resource(String name) {
		try (InputStream in = Standards.class.getResourceAsStream(name)) {
			if (in == null) {
				return null;
			}
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Recursively merges {@code patch} into {@code target}; JSON null removes a key. */
	public static void deepMerge(JsonObject target, JsonObject patch) {
		for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
			JsonElement v = e.getValue();
			if (v.isJsonObject() && target.has(e.getKey()) && target.get(e.getKey()).isJsonObject()) {
				deepMerge(target.getAsJsonObject(e.getKey()), v.getAsJsonObject());
			} else {
				target.add(e.getKey(), v.deepCopy());
			}
		}
	}

	/** Returns a new Standards with {@code patch} merged in. */
	public Standards patched(JsonObject patch) {
		JsonObject copy = data.deepCopy();
		deepMerge(copy, patch);
		JsonObject rulesNow = loadRules(copy.has("ruleset") ? copy.get("ruleset").getAsString() : null, source);
		Standards s = new Standards(copy, rulesNow, source);
		if (copy.has("units")) {
			Units.setSystem(UnitSystem.parse(copy.get("units").getAsString()));
		}
		return s;
	}

	public void save(Path path) throws IOException {
		Files.writeString(path, GSON.toJson(data));
	}

	public Path source() {
		return source;
	}

	public JsonObject data() {
		return data;
	}

	public JsonObject rules() {
		return rules;
	}

	public String json() {
		return GSON.toJson(data);
	}

	public String rulesJson() {
		return GSON.toJson(rules);
	}

	private static JsonElement path(JsonObject root, String dotted) {
		JsonElement cur = root;
		for (String part : dotted.split("\\.")) {
			if (cur == null || !cur.isJsonObject() || !cur.getAsJsonObject().has(part)) {
				return null;
			}
			cur = cur.getAsJsonObject().get(part);
		}
		return cur == null || cur.isJsonNull() ? null : cur;
	}

	/** A quantity from the team standards, SI; {@code fallback} when absent or null. */
	public double q(String dotted, Dim dim, double fallback) {
		return qOf(data, dotted, dim, fallback);
	}

	/** A quantity from the rule set, SI; NaN when the rule set does not define it. */
	public double rule(String dotted, Dim dim) {
		return qOf(rules, dotted, dim, Double.NaN);
	}

	public String ruleRef(String key) {
		JsonElement e = path(rules, key + ".ref");
		return e == null ? rules.has("id") ? rules.get("id").getAsString() : "" : e.getAsString();
	}

	public boolean hasRules() {
		return rules.size() > 0;
	}

	public String rulesName() {
		return rules.has("name") ? rules.get("name").getAsString() : "none";
	}

	public String str(String dotted, String fallback) {
		JsonElement e = path(data, dotted);
		return e == null ? fallback : e.getAsString();
	}

	public boolean bool(String dotted, boolean fallback) {
		JsonElement e = path(data, dotted);
		return e == null ? fallback : e.getAsBoolean();
	}

	private static double qOf(JsonObject root, String dotted, Dim dim, double fallback) {
		JsonElement e = path(root, dotted);
		if (e == null) {
			return fallback;
		}
		try {
			if (e.getAsJsonPrimitive().isNumber()) {
				return e.getAsDouble();
			}
			return Units.toSi(e.getAsString(), dim);
		} catch (RuntimeException ex) {
			throw new ToolException("Standards value '" + dotted + "' = " + e + " is invalid: " + ex.getMessage());
		}
	}

	/** Pin strength (N) by pin name from recovery.shearPins, NaN if unknown. */
	public double pinStrength(String name) {
		JsonElement pins = path(data, "recovery.shearPins");
		if (pins == null || !pins.isJsonObject()) {
			return Double.NaN;
		}
		for (Map.Entry<String, JsonElement> e : pins.getAsJsonObject().entrySet()) {
			if (e.getKey().equalsIgnoreCase(name.trim())) {
				JsonElement s = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("strength") : e.getValue();
				return s.getAsJsonPrimitive().isNumber() ? s.getAsDouble() : Units.toSi(s.getAsString(), Dim.FORCE);
			}
		}
		return Double.NaN;
	}

	/**
	 * Shear modulus (Pa) for a material name from structures.shearModulus, whose keys are '|'-separated
	 * case-insensitive name fragments (e.g. "fiberglass|g10"). Returns {modulus, key} or null.
	 */
	public Object[] shearModulus(String material) {
		JsonElement table = path(data, "structures.shearModulus");
		if (table == null || !table.isJsonObject() || material == null) {
			return null;
		}
		String m = material.toLowerCase(java.util.Locale.ROOT);
		// Team entries are merged after the defaults: search from the end so a team's own key wins over a default one.
		List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(table.getAsJsonObject().entrySet());
		Collections.reverse(entries);
		for (Map.Entry<String, JsonElement> e : entries) {
			for (String frag : e.getKey().toLowerCase(java.util.Locale.ROOT).split("\\|")) {
				if (!frag.isBlank() && m.contains(frag.trim())) {
					JsonElement v = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("value") : e.getValue();
					double g = v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : Units.toSi(v.getAsString(), Dim.PRESSURE);
					return new Object[] { g, e.getKey() };
				}
			}
		}
		return null;
	}

	public java.util.Set<String> pinNames() {
		JsonElement pins = path(data, "recovery.shearPins");
		return pins == null || !pins.isJsonObject() ? java.util.Set.of() : pins.getAsJsonObject().keySet();
	}

	/** Applies launch-site defaults to options of a newly created simulation. */
	public void applyLaunchDefaults(SimulationOptions opt) {
		double rod = q("launchSite.railLength", Dim.LENGTH, Double.NaN);
		if (!Double.isNaN(rod)) {
			opt.setLaunchRodLength(rod);
		}
		double angle = q("launchSite.launchAngleFromVertical", Dim.ANGLE, Double.NaN);
		if (!Double.isNaN(angle)) {
			opt.setLaunchRodAngle(angle);
		}
		double alt = q("launchSite.altitudeMsl", Dim.DISTANCE, Double.NaN);
		if (!Double.isNaN(alt)) {
			opt.setLaunchAltitude(alt);
		}
		double lat = q("launchSite.latitude", Dim.DIMENSIONLESS, Double.NaN);
		if (!Double.isNaN(lat)) {
			opt.setLaunchLatitude(lat);
		}
		double lon = q("launchSite.longitude", Dim.DIMENSIONLESS, Double.NaN);
		if (!Double.isNaN(lon)) {
			opt.setLaunchLongitude(lon);
		}
	}

	public static String pretty(Object o) {
		return GSON.toJson(o);
	}
}
