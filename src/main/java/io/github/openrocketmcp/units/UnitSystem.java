package io.github.openrocketmcp.units;

/** How quantities are displayed in tool output. */
public enum UnitSystem {
	METRIC, IMPERIAL, BOTH;

	public static UnitSystem parse(String s) {
		if (s == null) {
			return BOTH;
		}
		return switch (s.trim().toLowerCase()) {
			case "metric", "si" -> METRIC;
			case "imperial", "us", "english" -> IMPERIAL;
			case "both", "dual" -> BOTH;
			default -> throw new IllegalArgumentException("Unknown unit system '" + s + "' (use metric, imperial or both)");
		};
	}
}
