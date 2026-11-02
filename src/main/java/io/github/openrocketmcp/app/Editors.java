package io.github.openrocketmcp.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Bulkhead;
import info.openrocket.core.rocketcomponent.CenteringRing;
import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.EllipticalFinSet;
import info.openrocket.core.rocketcomponent.EngineBlock;
import info.openrocket.core.rocketcomponent.ExternalComponent;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FreeformFinSet;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.MassObject;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.ParallelStage;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.PodSet;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.Streamer;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import info.openrocket.core.rocketcomponent.TubeFinSet;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.units.Dim;

/**
 * The app's component editors: for each kind of part, the fields OpenRocket's component dialogs show, grouped the same
 * way (shape, size, material, position, overrides). Values are read and written through the same property access as
 * the edit_components tool, so the app and the chat change a design identically.
 */
public final class Editors {
	private Editors() {
	}

	/**
	 * One field. {@code kind}: quantity (with {@code dim}), number (no unit), count, bool, enum, material, text.
	 * {@code auto}: the boolean property that makes this value automatic (OpenRocket's "Automatic" box), or null.
	 * {@code when}: when the field applies (otherwise shown greyed out, as in OpenRocket): "massOverridden",
	 * "!filled", "shapeType=OGIVE|POWER"; null: always.
	 */
	public record Field(String key, String label, String kind, Dim dim, String auto, List<String[]> options, String help,
			String when) {
		Field(String key, String label, String kind, Dim dim, String auto, List<String[]> options, String help) {
			this(key, label, kind, dim, auto, options, help, null);
		}

		Field when(String condition) {
			return new Field(key, label, kind, dim, auto, options, help, condition);
		}
	}

	public record Section(String title, List<Field> fields) {
	}

	private static Field q(String key, String label, Dim dim) {
		return new Field(key, label, "quantity", dim, null, null, null);
	}

	private static Field qa(String key, String label, Dim dim, String auto) {
		return new Field(key, label, "quantity", dim, auto, null, null);
	}

	private static Field num(String key, String label) {
		return new Field(key, label, "number", null, null, null, null);
	}

	private static Field count(String key, String label) {
		return new Field(key, label, "count", null, null, null, null);
	}

	private static Field bool(String key, String label) {
		return new Field(key, label, "bool", null, null, null, null);
	}

	private static Field en(String key, String label, String... pairs) {
		List<String[]> o = new ArrayList<>();
		for (int i = 0; i < pairs.length; i += 2) {
			o.add(new String[] { pairs[i], pairs[i + 1] });
		}
		return new Field(key, label, "enum", null, null, o, null);
	}

	private static Field material(String key, String label) {
		return new Field(key, label, "material", null, null, null, null);
	}

	private static Field help(Field f, String help) {
		return new Field(f.key(), f.label(), f.kind(), f.dim(), f.auto(), f.options(), help, f.when());
	}

	private static Field auto(Field f, String auto) {
		return new Field(f.key(), f.label(), f.kind(), f.dim(), auto, f.options(), f.help(), f.when());
	}

	/** Shapes whose profile depends on the shape parameter. */
	static final String PARAMETRIC = "shapeType=OGIVE|POWER|PARABOLIC|HAACK";

	static final Field SHAPE = en("shapeType", "Shape", "CONICAL", "Conical", "OGIVE", "Ogive", "ELLIPSOID", "Ellipsoid",
			"POWER", "Power series", "PARABOLIC", "Parabolic series", "HAACK", "Haack series");
	static final Field FINISH = en("finish", "Surface finish", "ROUGH", "Rough (500 µm)", "ROUGHUNFINISHED",
			"Rough unfinished (250 µm)", "UNFINISHED", "Unfinished (150 µm)", "NORMAL", "Regular paint (60 µm)", "SMOOTH",
			"Smooth paint (20 µm)", "OPTIMUM", "Optimum paint (5 µm)", "POLISHED", "Polished (2 µm)", "FINISHPOLISHED",
			"Finished polished (0.5 µm)", "MIRROR", "Mirror (0 µm)");
	static final Field CROSS_SECTION = en("crossSection", "Cross section", "SQUARE", "Square", "ROUNDED", "Rounded", "AIRFOIL",
			"Airfoil");
	static final Field POSITION_METHOD = en("axialMethod", "Position relative to", "TOP", "Top of the parent",
			"MIDDLE", "Middle of the parent", "BOTTOM", "Bottom of the parent", "AFTER", "After the sibling above", "ABSOLUTE",
			"Tip of the nose cone");
	static final Field MASS_TYPE = en("massComponentType", "Type", "MASSCOMPONENT", "Mass component", "ALTIMETER", "Altimeter",
			"FLIGHTCOMPUTER", "Flight computer", "DEPLOYMENTCHARGE", "Deployment charge", "TRACKER", "Tracker", "PAYLOAD",
			"Payload", "RECOVERYHARDWARE", "Recovery hardware", "BATTERY", "Battery");

	/** The editor of a component: its sections in the order shown. */
	public static List<Section> of(RocketComponent c) {
		List<Section> out = new ArrayList<>();
		if (c instanceof Rocket) {
			out.add(new Section("Design", List.of(new Field("name", "Name", "text", null, null, null, null),
					new Field("designer", "Designer", "text", null, null, null, null),
					new Field("revision", "Revision", "text", null, null, null, null))));
			return out;
		}
		out.add(new Section("General", List.of(new Field("name", "Name", "text", null, null, null, null))));
		if (c instanceof NoseCone) {
			out.add(new Section("Shape", List.of(SHAPE, help(num("shapeParameter", "Shape parameter"),
					"Ogive: 1 is tangent. Power series: 0.5 is a half power. Haack: 0 is Von Kármán, 1/3 is LV-Haack.").when(PARAMETRIC),
					q("length", "Length", Dim.LENGTH), qa("aftRadius", "Base radius", Dim.LENGTH, "aftRadiusAutomatic"),
					q("thickness", "Wall thickness", Dim.LENGTH).when("!filled"), bool("filled", "Filled (solid)"),
					bool("flipped", "Flip to a tail cone"))));
			out.add(new Section("Shoulder", List.of(q("aftShoulderRadius", "Shoulder radius", Dim.LENGTH),
					q("aftShoulderLength", "Shoulder length", Dim.LENGTH), q("aftShoulderThickness", "Shoulder thickness", Dim.LENGTH),
					bool("aftShoulderCapped", "End capped"))));
		} else if (c instanceof Transition) {
			out.add(new Section("Shape", List.of(SHAPE, num("shapeParameter", "Shape parameter").when(PARAMETRIC),
					bool("clipped", "Clipped"), q("length", "Length", Dim.LENGTH),
					qa("foreRadius", "Fore radius", Dim.LENGTH, "foreRadiusAutomatic"),
					qa("aftRadius", "Aft radius", Dim.LENGTH, "aftRadiusAutomatic"), q("thickness", "Wall thickness", Dim.LENGTH).when("!filled"),
					bool("filled", "Filled (solid)"))));
			out.add(new Section("Shoulders", List.of(q("foreShoulderRadius", "Fore shoulder radius", Dim.LENGTH),
					q("foreShoulderLength", "Fore shoulder length", Dim.LENGTH),
					q("foreShoulderThickness", "Fore shoulder thickness", Dim.LENGTH), bool("foreShoulderCapped", "Fore end capped"),
					q("aftShoulderRadius", "Aft shoulder radius", Dim.LENGTH), q("aftShoulderLength", "Aft shoulder length", Dim.LENGTH),
					q("aftShoulderThickness", "Aft shoulder thickness", Dim.LENGTH), bool("aftShoulderCapped", "Aft end capped"))));
		} else if (c instanceof BodyTube) {
			out.add(new Section("Tube", List.of(q("length", "Length", Dim.LENGTH),
					qa("outerRadius", "Outer radius", Dim.LENGTH, "outerRadiusAutomatic"),
					q("innerRadius", "Inner radius", Dim.LENGTH).when("!filled"), q("thickness", "Wall thickness", Dim.LENGTH).when("!filled"),
					bool("filled", "Filled (solid)"))));
			out.add(new Section("Motor mount", List.of(bool("motorMount", "This is a motor mount"),
					q("motorOverhang", "Motor overhang", Dim.LENGTH).when("motorMount"))));
		} else if (c instanceof TrapezoidFinSet) {
			out.add(fins(c));
			out.add(new Section("Planform", List.of(q("rootChord", "Root chord", Dim.LENGTH), q("tipChord", "Tip chord", Dim.LENGTH),
					q("height", "Height (span)", Dim.LENGTH), q("sweep", "Sweep length", Dim.LENGTH),
					q("sweepAngle", "Sweep angle", Dim.ANGLE))));
			out.add(finSection());
			out.add(tabs());
		} else if (c instanceof EllipticalFinSet) {
			out.add(fins(c));
			out.add(new Section("Planform", List.of(q("length", "Root chord", Dim.LENGTH), q("height", "Height (span)", Dim.LENGTH))));
			out.add(finSection());
			out.add(tabs());
		} else if (c instanceof FreeformFinSet) {
			out.add(fins(c));
			out.add(finSection());
			out.add(tabs());
		} else if (c instanceof TubeFinSet) {
			out.add(new Section("Tube fins", List.of(count("finCount", "Number of tubes"), q("length", "Length", Dim.LENGTH),
					qa("outerRadius", "Outer radius", Dim.LENGTH, "outerRadiusAutomatic"), q("innerRadius", "Inner radius", Dim.LENGTH),
					q("thickness", "Wall thickness", Dim.LENGTH), q("baseRotation", "Rotation", Dim.ANGLE))));
		} else if (c instanceof LaunchLug) {
			out.add(new Section("Launch lug", List.of(q("length", "Length", Dim.LENGTH), q("outerRadius", "Outer radius", Dim.LENGTH),
					q("innerRadius", "Inner radius", Dim.LENGTH), q("thickness", "Wall thickness", Dim.LENGTH),
					q("angleOffset", "Angle around the tube", Dim.ANGLE), count("instanceCount", "Number of lugs"),
					q("instanceSeparation", "Spacing", Dim.LENGTH))));
		} else if (c instanceof RailButton) {
			out.add(new Section("Rail button", List.of(q("outerDiameter", "Outer diameter", Dim.LENGTH),
					q("innerDiameter", "Inner diameter", Dim.LENGTH), q("totalHeight", "Total height", Dim.LENGTH),
					q("baseHeight", "Base height", Dim.LENGTH), q("flangeHeight", "Flange height", Dim.LENGTH),
					q("screwHeight", "Screw height", Dim.LENGTH), q("angleOffset", "Angle around the tube", Dim.ANGLE),
					count("instanceCount", "Number of buttons"), q("instanceSeparation", "Spacing", Dim.LENGTH))));
		} else if (c instanceof InnerTube) {
			out.add(new Section("Tube", List.of(q("length", "Length", Dim.LENGTH), q("outerRadius", "Outer radius", Dim.LENGTH),
					q("innerRadius", "Inner radius", Dim.LENGTH), q("thickness", "Wall thickness", Dim.LENGTH),
					q("radialPosition", "Radial position", Dim.LENGTH), q("radialDirection", "Radial direction", Dim.ANGLE))));
			out.add(new Section("Motor mount", List.of(bool("motorMount", "This is a motor mount"),
					q("motorOverhang", "Motor overhang", Dim.LENGTH).when("motorMount"), num("clusterScale", "Cluster scale"),
					q("clusterRotation", "Cluster rotation", Dim.ANGLE))));
		} else if (c instanceof TubeCoupler) {
			out.add(new Section("Coupler", List.of(q("length", "Length", Dim.LENGTH),
					qa("outerRadius", "Outer radius", Dim.LENGTH, "outerRadiusAutomatic"), q("innerRadius", "Inner radius", Dim.LENGTH),
					q("thickness", "Wall thickness", Dim.LENGTH))));
		} else if (c instanceof CenteringRing) {
			out.add(new Section("Ring", List.of(q("length", "Thickness", Dim.LENGTH),
					qa("outerRadius", "Outer radius", Dim.LENGTH, "outerRadiusAutomatic"),
					qa("innerRadius", "Inner radius", Dim.LENGTH, "innerRadiusAutomatic"), count("instanceCount", "Number of rings"),
					q("instanceSeparation", "Spacing", Dim.LENGTH))));
		} else if (c instanceof Bulkhead) {
			out.add(new Section("Bulkhead", List.of(q("length", "Thickness", Dim.LENGTH),
					qa("outerRadius", "Outer radius", Dim.LENGTH, "outerRadiusAutomatic"))));
		} else if (c instanceof EngineBlock) {
			out.add(new Section("Engine block", List.of(q("length", "Length", Dim.LENGTH),
					qa("outerRadius", "Outer radius", Dim.LENGTH, "outerRadiusAutomatic"), q("innerRadius", "Inner radius", Dim.LENGTH),
					q("thickness", "Wall thickness", Dim.LENGTH))));
		} else if (c instanceof Parachute) {
			out.add(new Section("Canopy", List.of(q("diameter", "Diameter", Dim.LENGTH), auto(num("cd", "Drag coefficient"), "CDAutomatic"),
					material("material", "Canopy material"), count("lineCount", "Shroud lines"), q("lineLength", "Line length", Dim.LENGTH),
					material("lineMaterial", "Line material"))));
			out.add(packed());
		} else if (c instanceof Streamer) {
			out.add(new Section("Streamer", List.of(q("stripLength", "Strip length", Dim.LENGTH), q("stripWidth", "Strip width", Dim.LENGTH),
					auto(num("cd", "Drag coefficient"), "CDAutomatic"), material("material", "Material"))));
			out.add(packed());
		} else if (c instanceof ShockCord) {
			out.add(new Section("Shock cord", List.of(q("cordLength", "Cord length", Dim.LENGTH), material("material", "Material"))));
			out.add(packed());
		} else if (c instanceof MassComponent) {
			out.add(new Section("Mass", List.of(MASS_TYPE, q("componentMass", "Mass", Dim.MASS),
					q("length", "Length", Dim.LENGTH), qa("radius", "Radius", Dim.LENGTH, "radiusAutomatic"),
					q("radialPosition", "Radial position", Dim.LENGTH), q("radialDirection", "Radial direction", Dim.ANGLE))));
		} else if (c instanceof ParallelStage || c instanceof PodSet) {
			out.add(new Section(c instanceof PodSet ? "Pods" : "Boosters", List.of(count("instanceCount", "Number"),
					q("radiusOffset", "Radial distance", Dim.LENGTH), q("angleOffset", "Angle", Dim.ANGLE))));
		}
		if (!(c instanceof ComponentAssembly) && !(c instanceof MassObject)) {
			List<Field> mat = new ArrayList<>();
			if (has(c, "material")) {
				mat.add(material("material", "Material"));
			}
			if (c instanceof ExternalComponent && has(c, "finish")) {
				mat.add(FINISH);
			}
			if (!mat.isEmpty()) {
				out.add(new Section("Material", mat));
			}
		}
		if (!(c instanceof AxialStage) || c instanceof ParallelStage) {
			out.add(new Section("Position", List.of(POSITION_METHOD, q("axialOffset", "Offset", Dim.LENGTH))));
		}
		List<Field> o = new ArrayList<>();
		o.add(bool("massOverridden", "Override mass"));
		o.add(q("overrideMass", "Mass", Dim.MASS).when("massOverridden"));
		o.add(bool("CGOverridden", "Override CG"));
		o.add(q("overrideCGX", "CG from the part's front", Dim.LENGTH).when("CGOverridden"));
		if (c instanceof ExternalComponent || c instanceof ComponentAssembly) {
			o.add(bool("CDOverridden", "Override drag coefficient"));
			o.add(num("overrideCD", "Drag coefficient").when("CDOverridden"));
		}
		if (c.allowsChildren()) {
			o.add(bool("subcomponentsOverriddenMass", "Mass override includes the parts inside").when("massOverridden"));
		}
		out.add(new Section("Overrides", o));
		out.add(new Section("Comment", List.of(new Field("comment", "Notes", "longtext", null, null, null, null))));
		return out;
	}

	private static Section fins(RocketComponent c) {
		return new Section("Fins", List.of(count("finCount", "Number of fins"), q("baseRotation", "Rotation", Dim.ANGLE),
				q("cantAngle", "Cant angle", Dim.ANGLE)));
	}

	private static Section finSection() {
		return new Section("Cross section", List.of(CROSS_SECTION, q("thickness", "Thickness", Dim.LENGTH),
				q("filletRadius", "Fillet radius", Dim.LENGTH), material("filletMaterial", "Fillet material")));
	}

	private static Section tabs() {
		return new Section("Fin tabs", List.of(q("tabHeight", "Tab height", Dim.LENGTH), q("tabLength", "Tab length", Dim.LENGTH),
				q("tabOffset", "Tab position", Dim.LENGTH)));
	}

	private static Section packed() {
		return new Section("Packed size", List.of(q("length", "Packed length", Dim.LENGTH),
				qa("radius", "Packed radius", Dim.LENGTH, "radiusAutomatic"), q("radialPosition", "Radial position", Dim.LENGTH)));
	}

	static boolean has(RocketComponent c, String key) {
		try {
			Components.getRaw(c, key);
			return true;
		} catch (ToolException e) {
			return false;
		}
	}

	/** The editor with current values: sections of fields, each with its SI value (and whether it is automatic). */
	public static List<Map<String, Object>> render(RocketComponent c) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Section s : of(c)) {
			List<Map<String, Object>> fields = new ArrayList<>();
			for (Field f : s.fields()) {
				Object v;
				try {
					v = Components.getRaw(c, f.key());
				} catch (ToolException | IllegalArgumentException e) {
					continue; // a property this kind of part does not have in this OpenRocket version
				}
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("key", f.key());
				m.put("label", f.label());
				m.put("kind", f.kind());
				if (f.dim() != null) {
					m.put("dim", f.dim().name());
				}
				if (v instanceof Material mat) {
					m.put("value", mat.getName());
					m.put("materialType", mat.getType().name());
				} else if (v instanceof Enum<?> e) {
					m.put("value", e.name());
				} else if (v instanceof Double d && !Double.isFinite(d)) {
					m.put("value", null);
				} else {
					m.put("value", v);
				}
				if (f.auto() != null) {
					Object a = Components.getRaw(c, f.auto());
					m.put("auto", f.auto());
					m.put("isAuto", Boolean.TRUE.equals(a));
				}
				if (f.options() != null) {
					List<Map<String, String>> opts = new ArrayList<>();
					for (String[] o : f.options()) {
						opts.add(Map.of("value", o[0], "label", o[1]));
					}
					m.put("options", opts);
				}
				if (f.help() != null) {
					m.put("help", f.help());
				}
				if (f.when() != null && !holds(c, f.when())) {
					m.put("enabled", false);
				}
				fields.add(m);
			}
			if (!fields.isEmpty()) {
				Map<String, Object> sec = new LinkedHashMap<>();
				sec.put("title", s.title());
				sec.put("fields", fields);
				out.add(sec);
			}
		}
		return out;
	}

	/** Whether a field's condition holds for the component as it is now. */
	static boolean holds(RocketComponent c, String when) {
		try {
			if (when.startsWith("!")) {
				return !Boolean.TRUE.equals(Components.getRaw(c, when.substring(1)));
			}
			int eq = when.indexOf('=');
			if (eq > 0) {
				Object v = Components.getRaw(c, when.substring(0, eq));
				String name = v instanceof Enum<?> e ? e.name() : String.valueOf(v);
				return java.util.Arrays.asList(when.substring(eq + 1).split("\\|")).contains(name);
			}
			return Boolean.TRUE.equals(Components.getRaw(c, when));
		} catch (ToolException e) {
			return true;
		}
	}

	/** Whether {@code key} is a field of this component's editor (the app edits nothing else). */
	public static Field field(RocketComponent c, String key) {
		for (Section s : of(c)) {
			for (Field f : s.fields()) {
				if (f.key().equals(key) || key.equals(f.auto())) {
					return f;
				}
			}
		}
		return null;
	}
}
