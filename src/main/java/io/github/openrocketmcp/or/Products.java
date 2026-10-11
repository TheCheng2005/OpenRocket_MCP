package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.preset.ComponentPreset;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * What a product page says about the part: a parachute (diameter, Cd, weight, packed size), a motor (designation and
 * maker, or a ThrustCurve.org link), a catalog part (by part number in OpenRocket's parts database) or an item to
 * carry (altimeter, tracker, battery: weight and size). Read from the structured product data shops embed (JSON-LD)
 * and the visible text; values the page lacks come from a matching catalog entry where there is one, and every value
 * says where it came from.
 */
public final class Products {
	private Products() {
	}

	public enum Kind {
		PARACHUTE, MOTOR, PART, ITEM, UNKNOWN
	}

	/** A value in SI with its source: "page", "catalog: <part>", "given" or "assumed". */
	public record Value(double si, Dim dim, String source) {
		public String show() {
			return Units.fmt(si, dim) + " (" + source + ")";
		}
	}

	/** The page reduced to what matters: title, brand, part number, structured data and readable text. */
	public record Page(String url, String name, String brand, String sku, String text, String description) {
	}

	/** What the page describes. */
	public record Product(Kind kind, Page page, Map<String, Value> values, String motor, String motorMaker,
			ComponentPreset preset, List<String> notes) {
	}

	// ----------------------------------------------------------------------------------------------- the page

	private static final Pattern LD = Pattern.compile("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE);
	private static final Pattern ATTR = Pattern.compile("([a-zA-Z:_-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*')");

	/** Reads a page from its HTML (or plain text pasted from it). */
	public static Page read(String url, String html) {
		String name = null, brand = null, sku = null, description = null;
		StringBuilder structured = new StringBuilder();
		Matcher m = LD.matcher(html);
		while (m.find()) {
			try {
				for (JsonObject p : products(JsonParser.parseString(m.group(1).trim()))) {
					name = name == null ? str(p, "name") : name;
					description = description == null ? str(p, "description") : description;
					sku = sku == null ? first(str(p, "mpn"), str(p, "sku")) : sku;
					JsonElement b = p.get("brand");
					if (brand == null && b != null) {
						brand = b.isJsonObject() ? str(b.getAsJsonObject(), "name") : b.isJsonPrimitive() ? b.getAsString() : null;
					}
					if (p.has("weight") && p.get("weight").isJsonObject()) {
						JsonObject w = p.getAsJsonObject("weight");
						structured.append("\nWeight: ").append(str(w, "value")).append(' ')
								.append(first(str(w, "unitText"), unitCode(str(w, "unitCode"))));
					}
					if (p.has("additionalProperty") && p.get("additionalProperty").isJsonArray()) {
						for (JsonElement e : p.getAsJsonArray("additionalProperty")) {
							if (e.isJsonObject()) {
								structured.append('\n').append(str(e.getAsJsonObject(), "name")).append(": ")
										.append(str(e.getAsJsonObject(), "value"));
							}
						}
					}
				}
			} catch (RuntimeException notJson) {
				// a broken block is skipped; the visible text still has the specs
			}
		}
		Map<String, String> meta = meta(html);
		name = first(name, meta.get("og:title"), tag(html, "h1"), tag(html, "title"));
		brand = first(brand, meta.get("product:brand"), meta.get("og:brand"));
		description = first(description, meta.get("og:description"), meta.get("description"));
		String text = text(html) + structured;
		if (name == null) {
			// Pasted text: the product name is the first line that is not just the maker or a "Brand:" line.
			name = text.lines().map(String::trim).filter(l -> l.length() > 3 && !isMaker(l)
					&& !l.matches("(?i)(brand|manufacturer|made by|maker|vendor)\\s*[:\\-].*")).findFirst().orElse("product");
		}
		name = name.replaceAll("\\s*[|\\u2013\\u2014-]\\s*[^|\\u2013\\u2014-]*(shop|store|rocketry|recovery|chutes|llc|inc)\\.?\\s*$", "")
				.trim();
		if (brand == null) {
			brand = brandFromHost(url);
		}
		if (brand == null) {
			brand = brandFromText(name + "\n" + text);
		}
		if (sku == null) {
			Matcher s = Pattern.compile("(?i)\\b(?:part\\s*(?:no\\.?|number|#)|sku|item\\s*(?:no\\.?|#)|model)\\s*[:#]?\\s*([A-Z0-9][A-Z0-9./-]{2,})")
					.matcher(text);
			sku = s.find() ? s.group(1) : null;
		}
		return new Page(url, decode(name), brand == null ? null : decode(brand), sku, text, description == null ? null : decode(description));
	}

	private static List<JsonObject> products(JsonElement e) {
		List<JsonObject> out = new ArrayList<>();
		if (e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) {
				out.addAll(products(x));
			}
		} else if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			JsonElement t = o.get("@type");
			if (t != null && t.toString().contains("Product")) {
				out.add(o);
			}
			if (o.has("@graph")) {
				out.addAll(products(o.get("@graph")));
			}
		}
		return out;
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? null : e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static String unitCode(String c) {
		return c == null ? "" : switch (c.toUpperCase(Locale.ROOT)) {
			case "ONZ" -> "oz";
			case "GRM" -> "g";
			case "KGM" -> "kg";
			case "LBR" -> "lb";
			default -> c;
		};
	}

	@SafeVarargs
	private static <T> T first(T... xs) {
		for (T x : xs) {
			if (x != null && !(x instanceof String s && s.isBlank())) {
				return x;
			}
		}
		return null;
	}

	private static Map<String, String> meta(String html) {
		Map<String, String> out = new LinkedHashMap<>();
		Matcher m = META.matcher(html);
		while (m.find()) {
			Map<String, String> a = new LinkedHashMap<>();
			Matcher at = ATTR.matcher(m.group());
			while (at.find()) {
				a.put(at.group(1).toLowerCase(Locale.ROOT), at.group(2).substring(1, at.group(2).length() - 1));
			}
			String key = first(a.get("property"), a.get("name"), a.get("itemprop"));
			if (key != null && a.containsKey("content")) {
				out.putIfAbsent(key.toLowerCase(Locale.ROOT), a.get("content"));
			}
		}
		return out;
	}

	private static String tag(String html, String tag) {
		Matcher m = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
		return m.find() ? m.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim() : null;
	}

	/** Readable text: scripts and styles dropped, table cells as "label: value", one block per line. */
	static String text(String html) {
		String t = html.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ")
				.replaceAll("(?i)</t[hd]>\\s*<t[hd][^>]*>", ": ")
				.replaceAll("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6]|dt|dd|table|ul|section)>", "\n")
				.replaceAll("<[^>]+>", " ");
		StringBuilder out = new StringBuilder();
		for (String line : decode(t).split("\n")) {
			String l = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
			if (!l.isEmpty()) {
				out.append(l).append('\n');
			}
		}
		return out.toString();
	}

	static String decode(String s) {
		Matcher m = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").matcher(s);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String e = m.group(1), r;
			if (e.startsWith("#x") || e.startsWith("#X")) {
				r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
			} else if (e.startsWith("#")) {
				r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
			} else {
				r = switch (e) {
					case "amp" -> "&";
					case "quot" -> "\"";
					case "apos" -> "'";
					case "lt" -> "<";
					case "gt" -> ">";
					case "nbsp" -> " ";
					case "times" -> "x";
					case "deg" -> "deg";
					case "rdquo", "ldquo", "Prime" -> "\"";
					case "frac12" -> ".5";
					default -> " ";
				};
			}
			m.appendReplacement(out, Matcher.quoteReplacement(r));
		}
		m.appendTail(out);
		return out.toString().replace('\u201d', '"').replace('\u201c', '"').replace('\u2033', '"').replace('\u00d7', 'x');
	}

	/** Maker names OpenRocket's parts database uses, for shops whose pages do not name the brand. */
	private static final Map<String, String> HOSTS = Map.ofEntries(Map.entry("fruitychutes", "Fruity Chutes"),
			Map.entry("the-rocketman", "Rocketman"), Map.entry("rocketmanparachutes", "Rocketman"),
			Map.entry("topflightrecovery", "Top Flight"), Map.entry("locprecision", "LOC/Precision"),
			Map.entry("publicmissiles", "Public Missiles"), Map.entry("madcowrocketry", "Madcow"),
			Map.entry("giantleaprocketry", "Giant Leap"), Map.entry("aerotech-rocketry", "AeroTech"),
			Map.entry("pro38", "Cesaroni"), Map.entry("cesaronitech", "Cesaroni"), Map.entry("estesrockets", "Estes"),
			Map.entry("b2rocketry", "b2 Rocketry"), Map.entry("spherachutes", "Spherachutes"),
			Map.entry("featherweightaltimeters", "Featherweight"), Map.entry("eggtimerrocketry", "Eggtimer"),
			Map.entry("missileworks", "Missile Works"), Map.entry("perfectflite", "PerfectFlite"));

	private static volatile List<String> makers;

	/** Makers OpenRocket's catalog knows, plus the shops above. */
	private static List<String> makers() {
		if (makers == null) {
			java.util.Set<String> all = new java.util.LinkedHashSet<>(HOSTS.values());
			for (ComponentPreset.Type t : ComponentPreset.Type.values()) {
				for (ComponentPreset c : OrRuntime.presets().listForType(t)) {
					all.add(c.getManufacturer().getDisplayName());
				}
			}
			makers = List.copyOf(all);
		}
		return makers;
	}

	private static boolean isMaker(String line) {
		return makers().stream().anyMatch(m -> m.equalsIgnoreCase(line.trim()));
	}

	/** A "Brand: ..." line, else the first maker OpenRocket's catalog knows that the text names (pasted pages). */
	static String brandFromText(String text) {
		Matcher m = Pattern.compile("(?im)^\\s*(?:brand|manufacturer|made by|maker|vendor)\\s*[:\\-]\\s*(.+?)\\s*$").matcher(text);
		if (m.find()) {
			return m.group(1);
		}
		String lower = text.toLowerCase(Locale.ROOT);
		String best = null;
		int at = Integer.MAX_VALUE;
		for (String maker : makers()) {
			int i = maker.length() > 3 ? lower.indexOf(maker.toLowerCase(Locale.ROOT)) : -1;
			if (i >= 0 && i < at) {
				best = maker;
				at = i;
			}
		}
		return best;
	}

	static String brandFromHost(String url) {
		if (url == null) {
			return null;
		}
		String u = url.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, String> e : HOSTS.entrySet()) {
			if (u.contains(e.getKey())) {
				return e.getValue();
			}
		}
		return null;
	}

	// ----------------------------------------------------------------------------------------- what it is

	private static final Pattern THRUSTCURVE = Pattern.compile("(?i)thrustcurve\\.org/motors/([^/?#]+)/([^/?#]+)");
	/** Motor designations: "J350W", "L1115-P", "54-1706 K1100T", "H128W-14A". */
	private static final Pattern DESIGNATION = Pattern.compile("\\b([A-O]\\d{2,5}[A-Z]{0,3}(?:-(?:\\d{1,2}[A-Z]?|P|M|S|L))?)\\b");

	public static Product identify(Page p, Kind forced) {
		String name = p.name() == null ? "" : p.name();
		String lower = (name + " " + (p.description() == null ? "" : p.description())).toLowerCase(Locale.ROOT);
		String all = (lower + "\n" + p.text()).toLowerCase(Locale.ROOT);
		Kind kind = forced;
		String motor = null, maker = null;
		Matcher tc = p.url() == null ? null : THRUSTCURVE.matcher(p.url());
		if (tc != null && tc.find()) {
			maker = tc.group(1).replace('_', ' ').replace("%20", " ");
			motor = tc.group(2).replace("%20", " ");
			kind = kind == null ? Kind.MOTOR : kind;
		}
		if (kind == null || kind == Kind.MOTOR) {
			Matcher dm = DESIGNATION.matcher(name);
			if (motor == null && dm.find() && (kind == Kind.MOTOR
					|| lower.matches("(?s).*\\b(motor|reload|propellant|single.use|rms|pro\\d\\d|load|hybrid)\\b.*"))) {
				motor = dm.group(1);
				kind = Kind.MOTOR;
			}
		}
		if (kind == null && lower.matches("(?s).*\\b(parachute|chute|canopy|drogue)\\b.*")) {
			kind = Kind.PARACHUTE;
		}
		List<String> notes = new ArrayList<>();
		Map<String, Value> v = new LinkedHashMap<>();
		ComponentPreset preset = null;
		if (kind == null || kind == Kind.PART) {
			preset = presetByPartNo(p.sku(), p.brand(), name);
			if (preset != null) {
				kind = Kind.PART;
			}
		}
		if (kind == Kind.MOTOR) {
			if (maker == null) {
				maker = p.brand();
			}
			return new Product(Kind.MOTOR, p, v, motor, maker, null, notes);
		}
		if (kind == Kind.PARACHUTE) {
			preset = parachute(p, name, v, notes);
			return new Product(Kind.PARACHUTE, p, v, null, null, preset, notes);
		}
		if (kind == Kind.PART) {
			return new Product(Kind.PART, p, v, null, null, preset, notes);
		}
		// Anything else with a weight is something to carry (altimeter, tracker, battery, switch, hardware).
		weight(p.text(), v);
		dimensions(p.text(), v);
		if (kind == Kind.ITEM || v.containsKey("mass")) {
			if (!all.matches("(?s).*\\b(altimeter|gps|tracker|telemetry|battery|lipo|switch|flight computer|transmitter|"
					+ "receiver|camera|sled|bulkhead|u-bolt|eye ?bolt|quick ?link|swivel|harness|shock cord)\\b.*")) {
				notes.add("Not recognised as rocketry electronics or hardware; added as a mass item from its weight.");
			}
			return new Product(Kind.ITEM, p, v, null, null, null, notes);
		}
		return new Product(Kind.UNKNOWN, p, v, null, null, null, notes);
	}

	// ----------------------------------------------------------------------------------------- parachutes

	private static final String LEN = "(\"|''|in\\b|inch(?:es)?\\b|ft\\b|foot\\b|feet\\b|'|cm\\b|mm\\b|m\\b)";
	private static final Pattern NAME_SIZE = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*" + LEN);
	private static final Pattern DIAMETER = Pattern.compile("(?i)\\b(?:diameter|size|dia\\.?)\\b[^0-9\\n]{0,25}(\\d+(?:\\.\\d+)?)\\s*" + LEN);
	private static final Pattern CD = Pattern.compile("(?i)(?:\\bc\\s?d\\b|coefficient of drag|drag coefficient)[^0-9\\n]{0,12}(\\d*\\.\\d+|\\d+(?:\\.\\d+)?)");
	private static final Pattern WEIGHT = Pattern.compile("(?i)\\b(?:weight|weighs|mass)\\b[^0-9\\n]{0,15}(\\d+(?:\\.\\d+)?)\\s*(oz|ounces?|g|grams?|lbs?|pounds?|kg)\\b");
	private static final Pattern PACKED = Pattern.compile("(?i)\\bpack(?:ed|ing)?\\s*(?:size|dimensions?)?[^0-9\\n]{0,20}(\\d+(?:\\.\\d+)?)\\s*(?:\"|in|inch|inches|cm|mm)?\\s*(?:x|by)\\s*(\\d+(?:\\.\\d+)?)\\s*(\"|in\\b|inch(?:es)?|cm|mm)");
	private static final Pattern DIMS = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:\"|in|mm|cm)?\\s*x\\s*(\\d+(?:\\.\\d+)?)\\s*(?:\"|in|mm|cm)?\\s*(?:x\\s*(\\d+(?:\\.\\d+)?)\\s*)?(\"|in\\b|inch(?:es)?|mm|cm)");

	private static double len(String number, String unit) {
		String u = unit.toLowerCase(Locale.ROOT);
		u = u.equals("\"") || u.equals("''") || u.startsWith("inch") ? "in" : u.equals("'") || u.startsWith("f") ? "ft" : u;
		return Units.toSi(number + " " + u, Dim.LENGTH);
	}

	/** Fills diameter, cd, mass and packed size; returns the catalog entry that matched, if any. */
	private static ComponentPreset parachute(Page p, String name, Map<String, Value> v, List<String> notes) {
		Matcher d = DIAMETER.matcher(p.text());
		Matcher n = NAME_SIZE.matcher(name);
		double dia = Double.NaN;
		if (n.find()) {
			dia = len(n.group(1), n.group(2));
		} else if (d.find()) {
			dia = len(d.group(1), d.group(2));
		} else {
			// A size on a line that names the canopy ("Iris Ultra 84" Compact Parachute" under the shop's header).
			for (String line : p.text().split("\n")) {
				Matcher ln = NAME_SIZE.matcher(line);
				if (line.toLowerCase(Locale.ROOT).matches(".*\\b(parachute|chute|canopy|drogue)\\b.*") && ln.find()) {
					dia = len(ln.group(1), ln.group(2));
					break;
				}
			}
		}
		if (dia > 0.05 && dia < 30) {
			v.put("diameter", new Value(dia, Dim.LENGTH, "page"));
		}
		Matcher c = CD.matcher(p.text() + "\n" + name);
		while (c.find()) {
			double cd = Double.parseDouble(c.group(1));
			if (cd >= 0.3 && cd <= 3) {
				v.put("cd", new Value(cd, Dim.DIMENSIONLESS, "page"));
				break;
			}
		}
		weight(p.text(), v);
		Matcher pk = PACKED.matcher(p.text());
		if (pk.find()) {
			double a = len(pk.group(1), pk.group(3)), b = len(pk.group(2), pk.group(3));
			v.put("packedDiameter", new Value(Math.min(a, b), Dim.LENGTH, "page"));
			v.put("packedLength", new Value(Math.max(a, b), Dim.LENGTH, "page"));
		}
		ComponentPreset match = v.containsKey("diameter") ? catalogParachute(p.brand(), name, v.get("diameter").si()) : null;
		if (match != null) {
			String label = "catalog: " + match.getManufacturer().getSimpleName() + " " + match.getPartNo();
			if (!v.containsKey("cd") && match.has(ComponentPreset.CD)) {
				v.put("cd", new Value(match.get(ComponentPreset.CD), Dim.DIMENSIONLESS, label));
			}
			if (!v.containsKey("mass") && match.has(ComponentPreset.MASS)) {
				v.put("mass", new Value(match.get(ComponentPreset.MASS), Dim.MASS, label));
			}
			if (!v.containsKey("packedDiameter") && match.has(ComponentPreset.PACKED_DIAMETER) && match.has(ComponentPreset.PACKED_LENGTH)) {
				v.put("packedDiameter", new Value(match.get(ComponentPreset.PACKED_DIAMETER), Dim.LENGTH, label));
				v.put("packedLength", new Value(match.get(ComponentPreset.PACKED_LENGTH), Dim.LENGTH, label));
			}
		}
		if (!v.containsKey("cd")) {
			notes.add("The page gives no Cd and no catalog parachute matches it: 0.8 is assumed. Use the vendor's descent-rate "
					+ "chart (or pass cd) for the real one.");
			v.put("cd", new Value(0.8, Dim.DIMENSIONLESS, "assumed"));
		}
		return match;
	}

	/** The catalog parachute of this maker and diameter, preferring the one whose description shares the most words. */
	static ComponentPreset catalogParachute(String brand, String name, double diameter) {
		ComponentPreset best = null;
		int bestScore = -1;
		for (ComponentPreset c : OrRuntime.presets().listForType(ComponentPreset.Type.PARACHUTE)) {
			if (!c.has(ComponentPreset.DIAMETER) || Math.abs(c.get(ComponentPreset.DIAMETER) - diameter) > 0.013) {
				continue;
			}
			String maker = c.getManufacturer().getDisplayName().toLowerCase(Locale.ROOT);
			if (brand == null || !(maker.contains(brand.toLowerCase(Locale.ROOT)) || brand.toLowerCase(Locale.ROOT).contains(maker))) {
				continue;
			}
			String desc = (c.has(ComponentPreset.DESCRIPTION) ? c.get(ComponentPreset.DESCRIPTION) : "").toLowerCase(Locale.ROOT);
			int score = 0;
			for (String w : name.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
				if (w.length() > 3 && desc.contains(w)) {
					score++;
				}
			}
			if (score > bestScore) {
				best = c;
				bestScore = score;
			}
		}
		return best;
	}

	// ----------------------------------------------------------------------------------- parts and items

	/** A catalog part whose part number the page gives (or names in its title). */
	static ComponentPreset presetByPartNo(String sku, String brand, String name) {
		List<String> refs = new ArrayList<>();
		if (sku != null) {
			refs.add(sku);
		}
		Matcher m = Pattern.compile("\\b([A-Z]{1,5}-?\\d+(?:\\.\\d+)?[A-Z]{0,3})\\b").matcher(name == null ? "" : name);
		while (m.find()) {
			refs.add(m.group(1));
		}
		for (String ref : refs) {
			ComponentPreset found = null;
			int hits = 0;
			for (ComponentPreset.Type t : ComponentPreset.Type.values()) {
				for (ComponentPreset c : OrRuntime.presets().listForType(t)) {
					if (c.getPartNo().equalsIgnoreCase(ref) && (brand == null || hits == 0
							|| c.getManufacturer().getDisplayName().toLowerCase(Locale.ROOT).contains(brand.toLowerCase(Locale.ROOT)))) {
						if (found == null || brand != null && c.getManufacturer().getDisplayName().toLowerCase(Locale.ROOT)
								.contains(brand.toLowerCase(Locale.ROOT))) {
							found = c;
						}
						hits++;
					}
				}
			}
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static void weight(String text, Map<String, Value> v) {
		Matcher w = WEIGHT.matcher(text);
		if (w.find() && !v.containsKey("mass")) {
			String u = w.group(2).toLowerCase(Locale.ROOT);
			u = u.startsWith("ou") ? "oz" : u.startsWith("gr") ? "g" : u.startsWith("p") || u.startsWith("lb") ? "lb" : u;
			double m = Units.toSi(w.group(1) + " " + u, Dim.MASS);
			if (m > 0 && m < 100) {
				v.put("mass", new Value(m, Dim.MASS, "page"));
			}
		}
	}

	private static void dimensions(String text, Map<String, Value> v) {
		Matcher d = DIMS.matcher(text);
		if (d.find()) {
			String u = d.group(4);
			double a = len(d.group(1), u), b = len(d.group(2), u), c = d.group(3) == null ? 0 : len(d.group(3), u);
			double longest = Math.max(a, Math.max(b, c));
			if (longest > 0.005 && longest < 2) {
				v.put("length", new Value(longest, Dim.LENGTH, "page"));
			}
		}
	}
}
