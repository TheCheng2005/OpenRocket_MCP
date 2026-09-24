package io.github.openrocketmcp.or;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Component lookup, tree rendering and reflection-based property editing.
 *
 * <p>Components are addressed by the first 8 characters of their UUID ("a1b2c3d4"), the full UUID, or their
 * exact (case-insensitive) name when unique.
 */
public final class Components {

	/** Component types that can be created with add_component. */
	public static final List<String> CREATABLE = List.of(
			"AxialStage", "NoseCone", "BodyTube", "Transition", "TrapezoidFinSet", "EllipticalFinSet",
			"FreeformFinSet", "TubeFinSet", "LaunchLug", "RailButton", "InnerTube", "TubeCoupler", "CenteringRing",
			"Bulkhead", "EngineBlock", "Parachute", "Streamer", "ShockCord", "MassComponent", "ParallelStage",
			"PodSet");

	private static final Set<String> HIDDEN = Set.of("ID", "Name", "Parent", "Rocket", "Stage", "Class",
			"ComponentName", "PresetComponent", "Appearance", "InsideAppearance", "Color", "LineStyle", "Comment",
			"MotorConfigurationSet", "DeploymentConfigurations", "SeparationConfigurations", "Instances",
			"ClusterConfiguration", "ChildPosition", "FinPoints", "AxialMethod", "RadiusMethod", "AngleMethod",
			"Material", "LineMaterial", "SubcomponentsOverridden", "DisplayOrder_Side", "DisplayOrder_Back");

	private Components() {
	}

	public static String shortId(RocketComponent c) {
		return c.getID().toString().substring(0, 8);
	}

	public static RocketComponent find(Rocket rocket, String ref) {
		if (ref == null || ref.isBlank()) {
			throw new ToolException("A component reference (id or name) is required.");
		}
		String r = ref.trim();
		List<RocketComponent> byName = new ArrayList<>();
		for (RocketComponent c : rocket) {
			String id = c.getID().toString();
			if (id.equalsIgnoreCase(r) || (r.length() >= 6 && id.toLowerCase(Locale.ROOT).startsWith(r.toLowerCase(Locale.ROOT)))) {
				return c;
			}
			if (c.getName().equalsIgnoreCase(r)) {
				byName.add(c);
			}
		}
		if (byName.size() == 1) {
			return byName.get(0);
		}
		if (byName.size() > 1) {
			StringBuilder sb = new StringBuilder();
			for (RocketComponent c : byName) {
				sb.append(' ').append(shortId(c)).append(" (").append(c.getComponentName()).append(" in ")
						.append(c.getStage().getName()).append(")");
			}
			throw new ToolException("Several components are named '" + ref + "'; use an id:" + sb);
		}
		throw new ToolException("No component '" + ref + "'. Use get_design to list component ids.");
	}

	public static <T> T find(Rocket rocket, String ref, Class<T> type, String what) {
		RocketComponent c = find(rocket, ref);
		if (!type.isInstance(c)) {
			throw new ToolException("'" + c.getName() + "' is a " + c.getComponentName() + ", not a " + what + ".");
		}
		return type.cast(c);
	}

	/** Resolves a flight configuration by id prefix, index or name; defaults to the selected one. */
	public static FlightConfiguration config(Rocket rocket, String ref) {
		if (ref == null || ref.isBlank()) {
			return rocket.getSelectedConfiguration();
		}
		List<FlightConfigurationId> ids = rocket.getIds();
		for (FlightConfigurationId id : ids) {
			FlightConfiguration fc = rocket.getFlightConfiguration(id);
			if (id.toString().toLowerCase(Locale.ROOT).startsWith(ref.toLowerCase(Locale.ROOT))
					|| fc.getName().equalsIgnoreCase(ref)) {
				return fc;
			}
		}
		try {
			int idx = Integer.parseInt(ref.trim());
			if (idx >= 0 && idx < ids.size()) {
				return rocket.getFlightConfiguration(ids.get(idx));
			}
		} catch (NumberFormatException ignored) {
			// not an index
		}
		List<String> names = new ArrayList<>();
		for (FlightConfigurationId id : ids) {
			names.add(id.toString().substring(0, 8) + " \"" + rocket.getFlightConfiguration(id).getName() + "\"");
		}
		throw new ToolException("No flight configuration '" + ref + "'. Available: " + names);
	}

	/** Compact tree of the rocket for the model. */
	public static List<Map<String, Object>> tree(RocketComponent root) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (RocketComponent c : root.getChildren()) {
			out.add(node(c));
		}
		return out;
	}

	private static Map<String, Object> node(RocketComponent c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", shortId(c));
		m.put("name", c.getName());
		m.put("type", c.getClass().getSimpleName());
		if (c instanceof AxialStage s) {
			m.put("stageNumber", s.getStageNumber());
		}
		if (!(c instanceof AxialStage)) {
			m.put("length", Units.fmt(c.getLength(), Dim.LENGTH));
			try {
				m.put("top", Units.fmt(c.toAbsolute(info.openrocket.core.util.Coordinate.NUL)[0].x, Dim.LENGTH) + " from nose tip");
			} catch (RuntimeException e) {
				m.put("position", Units.fmt(c.getAxialOffset(), Dim.LENGTH));
			}
		}
		if (c instanceof BodyTube bt) {
			m.put("outerDiameter", Units.fmt(bt.getOuterRadius() * 2, Dim.LENGTH));
			m.put("innerDiameter", Units.fmt(bt.getInnerRadius() * 2, Dim.LENGTH));
		} else if (c instanceof SymmetricComponent sc) {
			m.put("foreDiameter", Units.fmt(sc.getForeRadius() * 2, Dim.LENGTH));
			m.put("aftDiameter", Units.fmt(sc.getAftRadius() * 2, Dim.LENGTH));
		}
		if (c instanceof RecoveryDevice rd) {
			m.put("cd", Units.num(rd.getCD()));
			m.put("area", Units.fmt(rd.getArea(), Dim.AREA));
		}
		if (c instanceof MotorMount mm && mm.isMotorMount()) {
			m.put("motorMount", true);
		}
		if (c instanceof AxialStage) {
			m.put("stageMass", Units.fmt(c.getSectionMass(), Dim.MASS) + " (without motors)");
		} else {
			m.put("mass", Units.fmt(c.getMass(), Dim.MASS) + (c.isMassOverridden() ? " (overridden)" : ""));
		}
		if (!c.getChildren().isEmpty()) {
			m.put("children", tree(c));
		}
		return m;
	}

	// ---------------------------------------------------------------- reflection-based property access

	/** Guesses the physical dimension of a double property from its name. */
	public static Dim dimOf(String property) {
		String p = property.toLowerCase(Locale.ROOT);
		if (p.contains("mass")) {
			return Dim.MASS;
		}
		if (p.contains("angle") || p.contains("sweep") || p.equals("cantangle") || p.contains("rotation")
				|| p.contains("direction")) {
			return Dim.ANGLE;
		}
		if (p.equals("cd") || p.contains("coefficient") || p.contains("shapeparameter") || p.contains("count")
				|| p.contains("ratio") || p.contains("scale")) {
			return Dim.DIMENSIONLESS;
		}
		if (p.contains("area")) {
			return Dim.AREA;
		}
		if (p.contains("altitude")) {
			return Dim.DISTANCE;
		}
		if (p.contains("delay") || p.contains("time")) {
			return Dim.TIME;
		}
		if (p.contains("radius") || p.contains("diameter") || p.contains("length") || p.contains("thickness")
				|| p.contains("height") || p.contains("span") || p.contains("chord") || p.contains("offset")
				|| p.contains("width") || p.contains("position") || p.contains("overhang") || p.contains("shift")
				|| p.contains("separation") || p.contains("cgx") || p.contains("depth")) {
			return Dim.LENGTH;
		}
		return Dim.DIMENSIONLESS;
	}

	private static boolean simpleType(Class<?> t) {
		return t == double.class || t == int.class || t == boolean.class || t == String.class || t.isEnum()
				|| t == Double.class || t == Integer.class || t == Boolean.class;
	}

	/** Editable properties (getter + matching single-argument setter) with current values. */
	public static Map<String, Object> describe(RocketComponent c) {
		Map<String, Object> props = new TreeMap<>();
		for (Method getter : c.getClass().getMethods()) {
			if (getter.getParameterCount() != 0 || Modifier.isStatic(getter.getModifiers())) {
				continue;
			}
			String name = getter.getName();
			String prop;
			if (name.startsWith("get") && name.length() > 3) {
				prop = name.substring(3);
			} else if (name.startsWith("is") && name.length() > 2) {
				prop = name.substring(2);
			} else {
				continue;
			}
			Class<?> type = getter.getReturnType();
			if (!simpleType(type) || HIDDEN.contains(prop) || setter(c, prop, type) == null) {
				continue;
			}
			try {
				Object v = getter.invoke(c);
				props.put(lowerFirst(prop), render(prop, type, v));
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// skip properties that cannot be read in the current state
			}
		}
		return props;
	}

	private static Object render(String prop, Class<?> type, Object v) {
		if (v == null) {
			return null;
		}
		if (type == double.class || type == Double.class) {
			Dim d = dimOf(prop);
			if (d == Dim.DIMENSIONLESS) {
				return Units.num((Double) v);
			}
			return Units.fmt((Double) v, d);
		}
		if (type.isEnum()) {
			Object[] constants = type.getEnumConstants();
			List<String> names = new ArrayList<>();
			for (Object o : constants) {
				names.add(((Enum<?>) o).name());
			}
			return ((Enum<?>) v).name() + " (one of " + String.join(", ", names) + ")";
		}
		return v;
	}

	private static Method setter(RocketComponent c, String prop, Class<?> type) {
		try {
			Method m = c.getClass().getMethod("set" + prop, type);
			return Modifier.isPublic(m.getModifiers()) ? m : null;
		} catch (NoSuchMethodException e) {
			if (type == Double.class) {
				return setter(c, prop, double.class);
			}
			return null;
		}
	}

	private static String lowerFirst(String s) {
		return Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}

	/**
	 * Sets one property by name (case-insensitive, e.g. "length", "outerRadius", "cd", "finCount").
	 * Returns a description of the change.
	 */
	public static String set(RocketComponent c, String property, JsonElement value) {
		String prop = property.trim();
		Method target = null;
		for (Method m : c.getClass().getMethods()) {
			if (m.getName().equalsIgnoreCase("set" + prop) && m.getParameterCount() == 1
					&& simpleType(m.getParameterTypes()[0])) {
				target = m;
				// prefer double over int overloads when the value is not integral
				if (m.getParameterTypes()[0] == double.class) {
					break;
				}
			}
		}
		// Friendly aliases for diameters of radius-based components.
		if (target == null && prop.toLowerCase(Locale.ROOT).endsWith("diameter")) {
			String radiusProp = prop.substring(0, prop.length() - "diameter".length()) + "Radius";
			double d = Units.toSi(value.isJsonPrimitive() && ((JsonPrimitive) value).isNumber()
					? (Object) value.getAsDouble() : value.getAsString(), Dim.LENGTH);
			return set(c, radiusProp.isEmpty() || radiusProp.equals("Radius") ? "radius" : radiusProp,
					new JsonPrimitive(d / 2)) + " (from diameter " + Units.fmt(d, Dim.LENGTH) + ")";
		}
		if (target == null) {
			throw new ToolException("'" + c.getName() + "' (" + c.getClass().getSimpleName() + ") has no settable property '"
					+ property + "'. Use describe_component to list properties.");
		}
		Class<?> type = target.getParameterTypes()[0];
		String canonical = target.getName().substring(3);
		Object arg;
		try {
			if (type == double.class || type == Double.class) {
				Dim d = dimOf(canonical);
				arg = value.isJsonPrimitive() && ((JsonPrimitive) value).isNumber()
						? value.getAsDouble()
						: Units.toSi(value.getAsString(), d);
			} else if (type == int.class || type == Integer.class) {
				arg = (int) Math.round(value.getAsDouble());
			} else if (type == boolean.class || type == Boolean.class) {
				arg = value.isJsonPrimitive() && ((JsonPrimitive) value).isBoolean()
						? value.getAsBoolean() : Boolean.parseBoolean(value.getAsString());
			} else if (type.isEnum()) {
				arg = enumValue(type, value.getAsString());
			} else {
				arg = value.getAsString();
			}
			target.invoke(c, arg);
		} catch (IllegalArgumentException e) {
			throw new ToolException("Property '" + canonical + "': " + e.getMessage());
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new ToolException("OpenRocket rejected " + canonical + " = " + value + ": " + cause.getMessage());
		} catch (ReflectiveOperationException e) {
			throw new ToolException("Could not set " + canonical + ": " + e.getMessage());
		}
		Object shown = arg instanceof Double dv ? render(canonical, double.class, dv) : arg;
		return c.getName() + "." + lowerFirst(canonical) + " = " + shown;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object enumValue(Class<?> type, String name) {
		for (Object o : type.getEnumConstants()) {
			if (((Enum) o).name().equalsIgnoreCase(name.trim())) {
				return o;
			}
		}
		List<String> names = new ArrayList<>();
		for (Object o : type.getEnumConstants()) {
			names.add(((Enum<?>) o).name());
		}
		throw new IllegalArgumentException("'" + name + "' is not one of " + names);
	}

	/** Raw (SI) value of a property, for exact restore after temporary edits. */
	public static Object getRaw(RocketComponent c, String property) {
		for (Method m : c.getClass().getMethods()) {
			String n = m.getName();
			if (m.getParameterCount() == 0 && (n.equalsIgnoreCase("get" + property) || n.equalsIgnoreCase("is" + property))) {
				try {
					return m.invoke(c);
				} catch (ReflectiveOperationException e) {
					throw new ToolException("Cannot read " + property + ": " + e.getMessage());
				}
			}
		}
		throw new ToolException("'" + c.getName() + "' has no property '" + property + "'. Use describe_component.");
	}

	/** Sets a property from a raw value previously read with {@link #getRaw}. */
	public static void setRaw(RocketComponent c, String property, Object value) {
		for (Method m : c.getClass().getMethods()) {
			if (m.getName().equalsIgnoreCase("set" + property) && m.getParameterCount() == 1
					&& (value == null || wrap(m.getParameterTypes()[0]).isInstance(value))) {
				try {
					m.invoke(c, value);
					return;
				} catch (ReflectiveOperationException e) {
					throw new ToolException("Cannot restore " + property + ": " + e.getMessage());
				}
			}
		}
	}

	private static Class<?> wrap(Class<?> t) {
		if (t == double.class) {
			return Double.class;
		}
		if (t == int.class) {
			return Integer.class;
		}
		if (t == boolean.class) {
			return Boolean.class;
		}
		return t;
	}

	/** Instantiates a component type by simple class name. */
	public static RocketComponent create(String type) {
		String match = null;
		for (String t : CREATABLE) {
			if (t.equalsIgnoreCase(type.trim())) {
				match = t;
			}
		}
		if (match == null) {
			throw new ToolException("Unknown component type '" + type + "'. Types: " + CREATABLE);
		}
		try {
			Class<?> cls = Class.forName("info.openrocket.core.rocketcomponent." + match);
			Constructor<?> ctor = cls.getConstructor();
			return (RocketComponent) ctor.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new ToolException("Cannot create " + match + ": " + e.getMessage());
		}
	}
}
