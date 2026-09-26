package io.github.openrocketmcp.units;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unit parsing and formatting. Inputs may be plain numbers (interpreted as SI) or strings with units
 * ("20 ft/s", "4.343in", "15 psi"). Output is formatted in the active {@link UnitSystem}.
 */
public final class Units {

	private record Unit(Dim dim, double factor, double offset) {
		double toSi(double v) {
			return v * factor + offset;
		}

		double fromSi(double v) {
			return (v - offset) / factor;
		}
	}

	private static final Map<String, Unit> UNITS = new HashMap<>();
	private static final Pattern QUANTITY = Pattern.compile(
			"^\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*(.*?)\\s*$");

	private static volatile UnitSystem system = UnitSystem.BOTH;

	static {
		def(Dim.LENGTH, 1, "m", "meter", "meters", "metre", "metres");
		def(Dim.LENGTH, 0.001, "mm");
		def(Dim.LENGTH, 0.01, "cm");
		def(Dim.LENGTH, 0.0254, "in", "inch", "inches", "\"");
		def(Dim.LENGTH, 0.3048, "ft", "foot", "feet", "'");
		def(Dim.LENGTH, 0.9144, "yd");
		def(Dim.LENGTH, 1000, "km");
		def(Dim.LENGTH, 1609.344, "mi", "mile", "miles");
		def(Dim.VELOCITY, 1, "m/s", "mps");
		def(Dim.VELOCITY, 1 / 3.6, "km/h", "kph", "kmh");
		def(Dim.VELOCITY, 0.3048, "ft/s", "fps", "ft/sec");
		def(Dim.VELOCITY, 0.44704, "mph");
		def(Dim.VELOCITY, 1852.0 / 3600, "kn", "kt", "knots");
		def(Dim.ACCELERATION, 1, "m/s2");
		def(Dim.ACCELERATION, 0.3048, "ft/s2");
		def(Dim.ACCELERATION, 9.80665, "G", "gee");
		def(Dim.MASS, 1, "kg");
		def(Dim.MASS, 0.001, "g", "gram", "grams");
		def(Dim.MASS, 0.45359237, "lb", "lbs", "lbm");
		def(Dim.MASS, 0.028349523125, "oz");
		def(Dim.FORCE, 1, "N");
		def(Dim.FORCE, 1000, "kN");
		def(Dim.FORCE, 4.4482216152605, "lbf");
		def(Dim.FORCE, 9.80665, "kgf");
		def(Dim.AREA, 1, "m2");
		def(Dim.AREA, 1e-4, "cm2");
		def(Dim.AREA, 1e-6, "mm2");
		def(Dim.AREA, 0.00064516, "in2");
		def(Dim.AREA, 0.09290304, "ft2");
		def(Dim.VOLUME, 1, "m3");
		def(Dim.VOLUME, 1e-6, "cm3", "cc", "ml", "mL");
		def(Dim.VOLUME, 1e-3, "L", "l");
		def(Dim.VOLUME, 1.6387064e-5, "in3");
		def(Dim.VOLUME, 0.028316846592, "ft3");
		def(Dim.PRESSURE, 1, "Pa");
		def(Dim.PRESSURE, 1000, "kPa");
		def(Dim.PRESSURE, 1e6, "MPa");
		def(Dim.PRESSURE, 1e9, "GPa");
		def(Dim.PRESSURE, 6894757.293168, "ksi");
		def(Dim.PRESSURE, 6894757293.168, "Msi");
		def(Dim.PRESSURE, 1e5, "bar");
		def(Dim.PRESSURE, 100, "mbar", "hPa");
		def(Dim.PRESSURE, 6894.757293168, "psi");
		def(Dim.PRESSURE, 101325, "atm");
		def(Dim.ENERGY, 1, "J");
		def(Dim.ENERGY, 1000, "kJ");
		def(Dim.ENERGY, 1.3558179483314, "ft-lbf", "ftlbf", "ft-lb", "lbf-ft");
		def(Dim.DENSITY, 1, "kg/m3");
		def(Dim.DENSITY, 1000, "g/cm3", "g/cc");
		def(Dim.DENSITY, 16.01846337396, "lb/ft3");
		def(Dim.TIME, 1, "s", "sec", "secs", "seconds");
		def(Dim.TIME, 0.001, "ms");
		def(Dim.TIME, 60, "min");
		def(Dim.ANGLE, 1, "rad");
		def(Dim.ANGLE, Math.PI / 180, "deg", "degrees", "°");
		def(Dim.IMPULSE, 1, "Ns", "N-s");
		def(Dim.IMPULSE, 4.4482216152605, "lbf-s", "lb-s", "lbfs");
		UNITS.put("K", new Unit(Dim.TEMPERATURE, 1, 0));
		UNITS.put("C", new Unit(Dim.TEMPERATURE, 1, 273.15));
		UNITS.put("degC", new Unit(Dim.TEMPERATURE, 1, 273.15));
		UNITS.put("F", new Unit(Dim.TEMPERATURE, 5.0 / 9, 273.15 - 32 * 5.0 / 9));
		UNITS.put("degF", new Unit(Dim.TEMPERATURE, 5.0 / 9, 273.15 - 32 * 5.0 / 9));
	}

	private static void def(Dim dim, double factor, String... names) {
		for (String n : names) {
			UNITS.put(n, new Unit(dim, factor, 0));
		}
	}

	private Units() {
	}

	public static UnitSystem system() {
		return system;
	}

	public static void setSystem(UnitSystem s) {
		system = s;
	}

	/** Normalizes unicode and punctuation variants so "ft·lbf", "in³", "m/s^2" and "N·s" parse. */
	private static String normalizeUnit(String u) {
		String s = u.trim()
				.replace("²", "2").replace("³", "3").replace("^", "")
				.replace("·", "-").replace("*", "-").replace("⋅", "-")
				.replace("°C", "C").replace("°F", "F")
				.replace(" ", "");
		if (s.equals("°")) {
			return s;
		}
		return s;
	}

	private static Unit lookup(String rawUnit) {
		String u = normalizeUnit(rawUnit);
		Unit unit = UNITS.get(u);
		if (unit == null) {
			// Case-insensitive fallback, except where case is meaningful (g vs G, N, K, C, F, L)
			for (Map.Entry<String, Unit> e : UNITS.entrySet()) {
				if (e.getKey().length() > 1 && e.getKey().equalsIgnoreCase(u)) {
					return e.getValue();
				}
			}
			// Trailing plural "s" (e.g. "Newtons" is not supported, but "inchs"/"feets" are harmless)
		}
		return unit;
	}

	/** Parsed input value. */
	public record Parsed(double si, Dim dim) {
	}

	/**
	 * Parses "20 ft/s" into SI. A bare number (no unit) returns {@code dim == null}; the caller decides
	 * whether that means SI.
	 */
	public static Parsed parse(String text) {
		Matcher m = QUANTITY.matcher(text);
		if (!m.matches()) {
			throw new IllegalArgumentException("Cannot parse quantity '" + text + "'. Use a number with a unit, e.g. \"20 ft/s\".");
		}
		double value = Double.parseDouble(m.group(1));
		String unit = m.group(2);
		if (unit.isEmpty()) {
			return new Parsed(value, null);
		}
		Unit u = lookup(unit);
		if (u == null) {
			throw new IllegalArgumentException("Unknown unit '" + unit + "' in '" + text + "'.");
		}
		return new Parsed(u.toSi(value), u.dim());
	}

	/**
	 * Converts a string or number into SI for the expected dimension. Numbers are SI. Grams are accepted for
	 * CHARGE_MASS and LENGTH units for DISTANCE (and vice versa), since those are the same physical dimension.
	 */
	public static double toSi(Object value, Dim expected) {
		if (value instanceof Number n) {
			return n.doubleValue();
		}
		Parsed p = parse(String.valueOf(value));
		if (p.dim() == null) {
			return p.si();
		}
		if (!compatible(p.dim(), expected)) {
			throw new IllegalArgumentException("'" + value + "' is a " + p.dim().name().toLowerCase(Locale.ROOT)
					+ " but a " + expected.name().toLowerCase(Locale.ROOT) + " was expected (e.g. "
					+ example(expected) + ").");
		}
		return p.si();
	}

	private static boolean compatible(Dim given, Dim expected) {
		if (given == expected) {
			return true;
		}
		if ((given == Dim.LENGTH && expected == Dim.DISTANCE) || (given == Dim.DISTANCE && expected == Dim.LENGTH)) {
			return true;
		}
		return (given == Dim.MASS && expected == Dim.CHARGE_MASS) || (given == Dim.CHARGE_MASS && expected == Dim.MASS);
	}

	public static String example(Dim d) {
		return switch (d) {
			case LENGTH -> "\"4.0 in\" or \"98 mm\"";
			case DISTANCE -> "\"1500 ft\" or \"457 m\"";
			case VELOCITY -> "\"20 ft/s\" or \"6 m/s\"";
			case ACCELERATION -> "\"10 G\" or \"30 m/s2\"";
			case MASS -> "\"12 lb\" or \"5.4 kg\"";
			case CHARGE_MASS -> "\"2.5 g\"";
			case FORCE -> "\"140 N\" or \"31 lbf\"";
			case AREA -> "\"6.8 ft2\" or \"0.63 m2\"";
			case VOLUME -> "\"112 in3\" or \"1.8 L\"";
			case PRESSURE -> "\"15 psi\" or \"103 kPa\"";
			case ENERGY -> "\"75 ft-lbf\" or \"101 J\"";
			case DENSITY -> "\"1.1 kg/m3\"";
			case TIME -> "\"2 s\"";
			case ANGLE -> "\"6 deg\"";
			case IMPULSE -> "\"5120 Ns\"";
			case TEMPERATURE -> "\"25 C\" or \"77 F\"";
			case DIMENSIONLESS -> "1.5";
		};
	}

	/** Converts an SI value into the given unit symbol. */
	public static double fromSi(double si, String unit) {
		Unit u = lookup(unit);
		if (u == null) {
			throw new IllegalArgumentException("Unknown unit " + unit);
		}
		return u.fromSi(si);
	}

	/** Unit for plot axes: imperial for an imperial team, else metric. */
	public static String plotUnit(Dim dim) {
		return system == UnitSystem.IMPERIAL ? dim.imperial : dim.metric;
	}

	/** Formats an SI value for display in the active unit system, e.g. "30.5 m/s (100 ft/s)". */
	public static String fmt(double si, Dim dim) {
		if (Double.isNaN(si)) {
			return "n/a";
		}
		if (dim == Dim.DIMENSIONLESS) {
			return num(si);
		}
		String mu = dim.metric, iu = dim.imperial;
		if (dim == Dim.MASS && Math.abs(si) < 1 && si != 0) {
			mu = "g";
		}
		if (dim == Dim.MASS && Math.abs(si) < 0.45359237 && si != 0) {
			iu = "oz";
		}
		if (dim == Dim.PRESSURE && Math.abs(si) >= 1e8) { // material properties: GPa / ksi
			mu = Math.abs(si) >= 1e9 ? "GPa" : "MPa";
			iu = "ksi";
		} else if (dim == Dim.PRESSURE && Math.abs(si) >= 1e7) {
			mu = "MPa";
		}
		String metric = num(fromSi(si, mu)) + " " + mu;
		String imperial = num(fromSi(si, iu)) + " " + iu;
		return switch (system) {
			case METRIC -> metric;
			case IMPERIAL -> imperial;
			case BOTH -> dim.metric.equals(dim.imperial) ? metric : metric + " (" + imperial + ")";
		};
	}

	/** Rounds to 4 significant figures without scientific notation for typical magnitudes. */
	public static String num(double v) {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			return String.valueOf(v);
		}
		if (v == 0) {
			return "0";
		}
		double abs = Math.abs(v);
		int digits = abs >= 1000 ? 0 : Math.max(0, 3 - (int) Math.floor(Math.log10(abs)));
		if (abs < 1e-4 || abs >= 1e9) {
			return String.format(Locale.ROOT, "%.3e", v);
		}
		String s = String.format(Locale.ROOT, "%." + digits + "f", v);
		if (s.contains(".")) {
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}
}
