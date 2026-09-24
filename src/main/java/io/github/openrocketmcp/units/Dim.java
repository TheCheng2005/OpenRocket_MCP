package io.github.openrocketmcp.units;

/**
 * Physical dimensions handled by the server. Values are always stored in SI internally
 * (angles in radians, temperatures in kelvin).
 */
public enum Dim {
	/** Component-scale lengths (diameters, fin spans). */
	LENGTH("m", "mm", "in"),
	/** Flight-scale distances (altitude, drift). */
	DISTANCE("m", "m", "ft"),
	VELOCITY("m/s", "m/s", "ft/s"),
	ACCELERATION("m/s2", "m/s2", "ft/s2"),
	MASS("kg", "kg", "lb"),
	/** Small masses that are conventionally grams in every unit system (black powder). */
	CHARGE_MASS("kg", "g", "g"),
	FORCE("N", "N", "lbf"),
	AREA("m2", "m2", "ft2"),
	VOLUME("m3", "cm3", "in3"),
	PRESSURE("Pa", "kPa", "psi"),
	ENERGY("J", "J", "ft-lbf"),
	DENSITY("kg/m3", "kg/m3", "lb/ft3"),
	TIME("s", "s", "s"),
	ANGLE("rad", "deg", "deg"),
	IMPULSE("Ns", "Ns", "lbf-s"),
	TEMPERATURE("K", "C", "F"),
	DIMENSIONLESS("", "", "");

	public final String si;
	public final String metric;
	public final String imperial;

	Dim(String si, String metric, String imperial) {
		this.si = si;
		this.metric = metric;
		this.imperial = imperial;
	}
}
