package io.github.openrocketmcp.calc;

/**
 * International Standard Atmosphere (troposphere + lower stratosphere), valid to 20 km.
 */
public final class Atmosphere {
	public static final double G0 = 9.80665;
	public static final double R_AIR = 287.053;
	private static final double T0 = 288.15;
	private static final double P0 = 101325;
	private static final double LAPSE = 0.0065;
	private static final double H_TROPOPAUSE = 11000;

	public record State(double altitudeMsl, double temperature, double pressure, double density) {
	}

	private Atmosphere() {
	}

	/**
	 * @param altitudeMsl        geometric altitude above mean sea level (m)
	 * @param temperatureOffset  deviation of the whole temperature profile from ISA (K); 0 for standard day
	 */
	public static State at(double altitudeMsl, double temperatureOffset) {
		double h = Math.max(-500, Math.min(altitudeMsl, 20000));
		double tIsa;
		double p;
		if (h <= H_TROPOPAUSE) {
			tIsa = T0 - LAPSE * h;
			p = P0 * Math.pow(tIsa / T0, G0 / (R_AIR * LAPSE));
		} else {
			double t11 = T0 - LAPSE * H_TROPOPAUSE;
			double p11 = P0 * Math.pow(t11 / T0, G0 / (R_AIR * LAPSE));
			tIsa = t11;
			p = p11 * Math.exp(-G0 * (h - H_TROPOPAUSE) / (R_AIR * t11));
		}
		double t = tIsa + temperatureOffset;
		return new State(altitudeMsl, t, p, p / (R_AIR * t));
	}

	public static State at(double altitudeMsl) {
		return at(altitudeMsl, 0);
	}
}
