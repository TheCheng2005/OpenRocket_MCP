package io.github.openrocketmcp.or;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Checks what someone said about the rocket ("apogee ~30k ft", "fins are 1/8 in G10", "stability at least 2 cal")
 * against the design and its simulation. The model reads the notes and states each claim; the numbers to compare
 * with come from here, never from the notes.
 */
public final class ClaimCheck {
	private ClaimCheck() {
	}

	/** A claimed value: a number (SI) with how it bounds the real one, or text (a motor, a material). */
	record Claimed(double si, String op, String text) {
	}

	private static final Pattern OP = Pattern.compile(
			"^(>=|<=|≥|≤|>|<|=|~|≈|about|around|approx(?:imately)?\\.?|roughly|at least|at most|no more than|no less than|"
					+ "not more than|not less than|more than|less than|over|under|above|below|min(?:imum)?|max(?:imum)?)\\s*",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern KILO = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*k\\b\\s*", Pattern.CASE_INSENSITIVE);

	/** Parses "~30k ft", ">= 2 cal", "under 25 lb", "Mach 1.3". Dim null: a plain number (calibers, Mach, margins). */
	static Claimed parse(String raw, Dim dim) {
		String s = raw.trim().replace(",", "");
		String op = "=";
		Matcher m = OP.matcher(s);
		if (m.find()) {
			String w = m.group(1).toLowerCase(Locale.ROOT);
			op = switch (w) {
				case ">=", "≥", "at least", "no less than", "not less than", "min", "minimum" -> ">=";
				case "<=", "≤", "at most", "no more than", "not more than", "max", "maximum" -> "<=";
				case ">", "more than", "over", "above" -> ">";
				case "<", "less than", "under", "below" -> "<";
				case "=" -> "=";
				default -> "~";
			};
			s = s.substring(m.end());
		}
		s = s.replaceFirst("(?i)^mach\\s*", "");
		Matcher k = KILO.matcher(s);
		if (k.find()) {
			s = k.replaceFirst(Units.num(Double.parseDouble(k.group(1)) * 1000) + " ");
		}
		s = s.trim();
		if (dim == null) {
			s = s.replaceFirst("(?i)\\s*(cal|cals|calibers?|calibres?|x)$", "").trim();
			try {
				return new Claimed(Double.parseDouble(s), op, null);
			} catch (NumberFormatException e) {
				throw new ToolException("'" + raw + "' is not a number.");
			}
		}
		if (s.matches("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?")) {
			// Notes quote "30k" meaning feet as often as metres: a bare number is not guessed.
			throw new ToolException("'" + raw + "' has no unit; give it as written with the unit, e.g. \"30000 ft\" or \"9144 m\".");
		}
		return new Claimed(Units.toSi(s, dim), op, null);
	}

	/** Tolerance "5%" (relative) or a quantity ("200 ft", "0.2 cal"): {relative, absolute}. */
	static double[] tolerance(String raw, Dim dim) {
		String s = raw.trim();
		if (s.endsWith("%")) {
			double p = Double.parseDouble(s.substring(0, s.length() - 1).trim());
			if (!(p >= 0 && p < 100)) {
				throw new ToolException("Tolerance " + raw + " must be 0-100%.");
			}
			return new double[] { p / 100, 0 };
		}
		double a = parse(s, dim).si();
		if (!(a >= 0)) {
			throw new ToolException("Tolerance " + raw + " must not be negative.");
		}
		return new double[] { 0, a };
	}

	/** Checks one claim; a claim that cannot be read is reported UNCHECKED with the reason, not an error for the batch. */
	public static Map<String, Object> checkOrExplain(Map<String, ProjectFacts.Fact> facts, Rocket rocket, String quantity,
			String component, String property, String value, String tolerance, String source) {
		try {
			return check(facts, rocket, quantity, component, property, value, tolerance, source);
		} catch (ToolException | IllegalArgumentException e) {
			Map<String, Object> out = new LinkedHashMap<>();
			if (source != null && !source.isBlank()) {
				out.put("source", source);
			}
			String what = component != null && !component.isBlank() ? (component + " " + (property == null ? "" : property)).trim()
					: quantity != null && !quantity.isBlank() ? quantity : null;
			out.put("claim", what == null && (value == null || value.isBlank()) ? "(empty claim)"
					: (what == null ? "?" : what) + ": " + (value == null || value.isBlank() ? "(no value)" : value));
			out.put("status", "UNCHECKED");
			out.put("why", e.getMessage());
			return out;
		}
	}

	/** Checks one claim. {@code facts}: from {@link ProjectFacts#of}. */
	public static Map<String, Object> check(Map<String, ProjectFacts.Fact> facts, Rocket rocket, String quantity, String component,
			String property, String value, String tolerance, String source) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (source != null && !source.isBlank()) {
			out.put("source", source);
		}
		if (value == null || value.isBlank()) {
			throw new ToolException("Each claim needs a value (what the notes say), e.g. \"30000 ft\" or \"G10\".");
		}
		if (component != null && !component.isBlank()) {
			return component(out, rocket, component, property == null || property.isBlank() ? "material" : property, value, tolerance);
		}
		if (quantity == null || quantity.isBlank()) {
			throw new ToolException("Each claim needs a quantity (e.g. \"apogee\") or a component and property.");
		}
		String key = ProjectFacts.key(quantity);
		out.put("claim", quantity + ": " + value);
		if (key == null) {
			out.put("status", "UNCHECKED");
			out.put("why", "Not a quantity this tool knows. Known: " + String.join(", ", ProjectFacts.known())
					+ "; or give component and property for a part.");
			return out;
		}
		ProjectFacts.Fact f = facts.get(key);
		if (f == null) {
			out.put("status", "UNCHECKED");
			out.put("why", "The design has no " + key + " to compare with (e.g. no fin material stiffness, no second recovery device).");
			return out;
		}
		out.put("actual", f.shown());
		if (f.text() != null) {
			boolean same = normalize(f.text()).contains(normalize(value)) || normalize(value).contains(normalize(f.text()));
			out.put("status", same ? "MATCH" : "MISMATCH");
			return out;
		}
		Claimed c = parse(value, f.dim());
		double[] tol = tolerance != null && !tolerance.isBlank() ? tolerance(tolerance, f.dim())
				: new double[] { "~".equals(c.op()) ? Math.max(f.relTol(), 0.05) : f.relTol(), f.absTol() };
		double allowed = Math.max(tol[0] * Math.abs(c.si()), tol[1]);
		double a = f.si(), diff = a - c.si();
		boolean ok = switch (c.op()) {
			case ">=" -> a >= c.si() - allowed;
			case "<=" -> a <= c.si() + allowed;
			case ">" -> a > c.si();
			case "<" -> a < c.si();
			default -> Math.abs(diff) <= allowed;
		};
		out.put("status", ok ? "MATCH" : "MISMATCH");
		if (c.op().equals("=") || c.op().equals("~")) {
			out.put("difference", (diff >= 0 ? "+" : "-") + (f.dim() == null ? Units.num(Math.abs(diff)) + (f.unitText().isEmpty() ? "" : " " + f.unitText())
					: Units.fmt(Math.abs(diff), f.dim())) + (c.si() != 0 ? " (" + (diff >= 0 ? "+" : "") + Units.num(100 * diff / Math.abs(c.si())) + "%)" : ""));
			out.put("tolerance", tol[0] > 0 && tol[0] * Math.abs(c.si()) >= tol[1] ? Units.num(100 * tol[0]) + "%"
					: f.dim() == null ? Units.num(tol[1]) : Units.fmt(tol[1], f.dim()));
		}
		return out;
	}

	/** A part's property ("Fins" thickness "1/8 in", nose cone material "fiberglass", "Main" diameter "84 in"). */
	static Map<String, Object> component(Map<String, Object> out, Rocket rocket, String component, String property, String value,
			String tolerance) {
		RocketComponent c = part(rocket, component);
		out.put("claim", c.getName() + " " + property + ": " + value);
		Map<String, Object> props = Components.describe(c);
		String match = null;
		for (String k : props.keySet()) {
			if (k.equalsIgnoreCase(property) || k.equalsIgnoreCase(property.replace(" ", ""))) {
				match = k;
			}
		}
		if (match == null) {
			out.put("status", "UNCHECKED");
			out.put("why", c.getName() + " has no property '" + property + "'. It has: " + String.join(", ", props.keySet()));
			return out;
		}
		Object raw = Components.getRaw(c, match);
		String shown = String.valueOf(props.get(match)).replaceAll("\\s*\\(one of .*\\)$", "").replaceAll(";\\s*set by name.*$", ")");
		out.put("actual", shown);
		if (raw instanceof Double v) {
			Dim dim = Components.dimOf(match);
			Claimed cl = parse(value, dim == Dim.DIMENSIONLESS ? null : dim);
			double[] tol = tolerance != null && !tolerance.isBlank() ? tolerance(tolerance, dim == Dim.DIMENSIONLESS ? null : dim)
					: new double[] { "~".equals(cl.op()) ? 0.05 : 0.02, 0 };
			double allowed = Math.max(tol[0] * Math.abs(cl.si()), tol[1]);
			boolean ok = switch (cl.op()) {
				case ">=" -> v >= cl.si() - allowed;
				case "<=" -> v <= cl.si() + allowed;
				case ">" -> v > cl.si();
				case "<" -> v < cl.si();
				default -> Math.abs(v - cl.si()) <= allowed;
			};
			out.put("status", ok ? "MATCH" : "MISMATCH");
			return out;
		}
		boolean same = normalize(shown).contains(normalize(value));
		out.put("status", same ? "MATCH" : "MISMATCH");
		return out;
	}

	/**
	 * The part notes mean: its name or id, else the word people use for its kind ("fins", "nose cone", "main",
	 * "drogue", "motor mount") when the design has one such part (or the drogue and main by deployment order).
	 */
	static RocketComponent part(Rocket rocket, String ref) {
		try {
			return Components.find(rocket, ref);
		} catch (ToolException e) {
			String k = normalize(ref);
			Class<?> type = switch (k) {
				case "fins", "fin", "finset", "fincan" -> info.openrocket.core.rocketcomponent.FinSet.class;
				case "nose", "nosecone", "nosecones" -> info.openrocket.core.rocketcomponent.NoseCone.class;
				case "transition", "boattail", "tailcone" -> info.openrocket.core.rocketcomponent.Transition.class;
				case "main", "mainchute", "mainparachute", "drogue", "droguechute", "drogueparachute", "parachute", "chute" ->
					info.openrocket.core.rocketcomponent.RecoveryDevice.class;
				case "motormount", "mount", "motortube" -> info.openrocket.core.rocketcomponent.MotorMount.class;
				case "railbuttons", "railbutton", "buttons" -> info.openrocket.core.rocketcomponent.RailButton.class;
				default -> null;
			};
			if (type == null) {
				throw e;
			}
			java.util.List<RocketComponent> found = new java.util.ArrayList<>();
			for (RocketComponent c : rocket) {
				boolean ok = type.isInstance(c) && !(type == info.openrocket.core.rocketcomponent.Transition.class
						&& c instanceof info.openrocket.core.rocketcomponent.NoseCone);
				if (ok && (type != info.openrocket.core.rocketcomponent.MotorMount.class
						|| ((info.openrocket.core.rocketcomponent.MotorMount) c).isMotorMount())) {
					found.add(c);
				}
			}
			if (found.size() > 1 && (k.startsWith("main") || k.startsWith("drogue"))) {
				// Named by role: a device with that word in its name, else the drogue opens at apogee and the main at an altitude.
				String role = k.startsWith("main") ? "main" : "drogue";
				for (RocketComponent c : found) {
					if (normalize(c.getName()).contains(role)) {
						return c;
					}
				}
				for (RocketComponent c : found) {
					var ev = ((info.openrocket.core.rocketcomponent.RecoveryDevice) c).getDeploymentConfigurations().getDefault().getDeployEvent();
					boolean apogee = ev == info.openrocket.core.rocketcomponent.DeploymentConfiguration.DeployEvent.APOGEE
							|| ev == info.openrocket.core.rocketcomponent.DeploymentConfiguration.DeployEvent.EJECTION;
					boolean altitude = ev == info.openrocket.core.rocketcomponent.DeploymentConfiguration.DeployEvent.ALTITUDE;
					if (role.equals("drogue") ? apogee : altitude) {
						return c;
					}
				}
			}
			if (found.size() == 1) {
				return found.get(0);
			}
			if (found.isEmpty()) {
				throw e;
			}
			java.util.List<String> names = new java.util.ArrayList<>();
			for (RocketComponent c : found) {
				names.add(c.getName());
			}
			throw new ToolException("'" + ref + "' could be any of " + String.join(", ", names) + "; name the part.");
		}
	}

	static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
