package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.preset.TypedKey;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** OpenRocket's parts database (body tubes, nose cones, couplers, rings, rail buttons, chutes...). */
public final class Presets {
	private Presets() {
	}

	public static ComponentPreset.Type type(String name) {
		String n = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("-", "_");
		n = switch (n) {
			case "BODYTUBE", "TUBE", "AIRFRAME" -> "BODY_TUBE";
			case "NOSECONE", "NOSE" -> "NOSE_CONE";
			case "COUPLER", "TUBECOUPLER" -> "TUBE_COUPLER";
			case "BULKHEAD" -> "BULK_HEAD";
			case "CENTERINGRING", "RING" -> "CENTERING_RING";
			case "RAILBUTTON", "BUTTON" -> "RAIL_BUTTON";
			case "LAUNCHLUG", "LUG" -> "LAUNCH_LUG";
			case "ENGINEBLOCK", "THRUST_RING" -> "ENGINE_BLOCK";
			default -> n;
		};
		try {
			return ComponentPreset.Type.valueOf(n);
		} catch (IllegalArgumentException e) {
			throw new ToolException("Unknown part type '" + name + "'. Types: " + java.util.Arrays.toString(ComponentPreset.Type.values()));
		}
	}

	static double dbl(ComponentPreset p, TypedKey<Double> k) {
		return p.has(k) ? p.get(k) : Double.NaN;
	}

	/** Characteristic outer diameter: outer diameter, aft diameter (nose cones / transitions), or diameter. */
	public static double outerDiameter(ComponentPreset p) {
		double d = dbl(p, ComponentPreset.OUTER_DIAMETER);
		if (Double.isNaN(d)) {
			d = dbl(p, ComponentPreset.AFT_OUTER_DIAMETER);
		}
		if (Double.isNaN(d)) {
			d = dbl(p, ComponentPreset.DIAMETER);
		}
		return d;
	}

	public static String name(ComponentPreset p) {
		return p.getManufacturer().getDisplayName() + " " + p.getPartNo();
	}

	/** Filters by type, outer / inner diameter window, manufacturer and free text; sorted by diameter. */
	public static List<ComponentPreset> search(ComponentPreset.Type type, double minOd, double maxOd, double minId,
			double maxId, String manufacturer, String text) {
		List<ComponentPreset> out = new ArrayList<>();
		for (ComponentPreset p : OrRuntime.presets().listForType(type)) {
			double od = outerDiameter(p), id = dbl(p, ComponentPreset.INNER_DIAMETER);
			if (!Double.isNaN(minOd) && !(od >= minOd - 1e-4) || !Double.isNaN(maxOd) && !(od <= maxOd + 1e-4)) {
				continue;
			}
			if (!Double.isNaN(minId) && !(id >= minId - 1e-4) || !Double.isNaN(maxId) && !(id <= maxId + 1e-4)) {
				continue;
			}
			if (manufacturer != null && !p.getManufacturer().getDisplayName().toLowerCase(Locale.ROOT)
					.contains(manufacturer.toLowerCase(Locale.ROOT))) {
				continue;
			}
			if (text != null) {
				String hay = (name(p) + " " + (p.has(ComponentPreset.DESCRIPTION) ? p.get(ComponentPreset.DESCRIPTION) : "") + " "
						+ (p.has(ComponentPreset.MATERIAL) ? p.get(ComponentPreset.MATERIAL).getName() : "")).toLowerCase(Locale.ROOT);
				boolean all = true;
				for (String w : text.toLowerCase(Locale.ROOT).split("\\s+")) {
					all &= hay.contains(w);
				}
				if (!all) {
					continue;
				}
			}
			out.add(p);
		}
		out.sort((a, b) -> Double.compare(nanLast(outerDiameter(a)), nanLast(outerDiameter(b))));
		return out;
	}

	private static double nanLast(double v) {
		return Double.isNaN(v) ? Double.MAX_VALUE : v;
	}

	public static Map<String, Object> render(ComponentPreset p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("preset", name(p));
		if (p.has(ComponentPreset.DESCRIPTION)) {
			m.put("description", p.get(ComponentPreset.DESCRIPTION));
		}
		put(m, "outerDiameter", dbl(p, ComponentPreset.OUTER_DIAMETER), Dim.LENGTH);
		put(m, "innerDiameter", dbl(p, ComponentPreset.INNER_DIAMETER), Dim.LENGTH);
		put(m, "aftDiameter", dbl(p, ComponentPreset.AFT_OUTER_DIAMETER), Dim.LENGTH);
		put(m, "diameter", dbl(p, ComponentPreset.DIAMETER), Dim.LENGTH);
		put(m, "length", dbl(p, ComponentPreset.LENGTH), Dim.LENGTH);
		put(m, "thickness", dbl(p, ComponentPreset.THICKNESS), Dim.LENGTH);
		put(m, "aftShoulderLength", dbl(p, ComponentPreset.AFT_SHOULDER_LENGTH), Dim.LENGTH);
		put(m, "mass", dbl(p, ComponentPreset.MASS), Dim.MASS);
		if (p.has(ComponentPreset.SHAPE)) {
			m.put("shape", p.get(ComponentPreset.SHAPE).name());
		}
		if (p.has(ComponentPreset.MATERIAL)) {
			m.put("material", p.get(ComponentPreset.MATERIAL).getName());
		}
		if (p.has(ComponentPreset.CD)) {
			m.put("cd", Units.num(p.get(ComponentPreset.CD)));
		}
		return m;
	}

	private static void put(Map<String, Object> m, String k, double v, Dim d) {
		if (!Double.isNaN(v) && v > 0) {
			m.put(k, Units.fmt(v, d));
		}
	}

	/** Finds a preset by "<manufacturer> <part no>" or part number alone, for the component's type. */
	public static ComponentPreset find(ComponentPreset.Type type, String ref) {
		String r = ref.trim().toLowerCase(Locale.ROOT);
		ComponentPreset partOnly = null;
		int partMatches = 0;
		for (ComponentPreset p : OrRuntime.presets().listForType(type)) {
			if (name(p).toLowerCase(Locale.ROOT).equals(r)) {
				return p;
			}
			if (p.getPartNo().toLowerCase(Locale.ROOT).equals(r)) {
				partOnly = p;
				partMatches++;
			}
		}
		if (partMatches == 1) {
			return partOnly;
		}
		throw new ToolException(partMatches > 1 ? "Part number '" + ref + "' exists for several manufacturers; give "
				+ "\"<manufacturer> <part no>\"." : "No " + type + " preset '" + ref + "'. Use search_parts.");
	}

	/** Loads a preset into a component (dimensions, material, mass as OpenRocket defines for the type). */
	public static ComponentPreset apply(RocketComponent c, String ref) {
		ComponentPreset.Type t = c.getPresetType();
		if (t == null) {
			throw new ToolException(c.getComponentName() + " components have no parts-database presets.");
		}
		ComponentPreset p = find(t, ref);
		c.loadPreset(p);
		return p;
	}
}
