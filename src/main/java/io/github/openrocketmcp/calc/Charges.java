package io.github.openrocketmcp.calc;

/**
 * Black powder ejection charge and shear pin sizing.
 *
 * <p>The black powder estimate is the standard ideal-gas method used by hobby BP calculators:
 * m = P V / (R T), with the combustion-gas constants for FFFFg black powder R = 266 in·lbf/(lbm·°R) and
 * T = 3307 °R. At 15 psi this reduces to the familiar rule of thumb grams ≈ 0.006 · D[in]² · L[in].
 * Always confirm with a ground test.
 */
public final class Charges {
	/** Combustion gas constant for black powder, in·lbf/(lbm·°R). */
	public static final double R_BP = 266.0;
	/** Combustion temperature for black powder, °R. */
	public static final double T_BP = 3307.0;
	private static final double PSI = 6894.757293168;
	private static final double IN3 = 1.6387064e-5;
	private static final double LBM_TO_G = 453.59237;

	private Charges() {
	}

	/** Grams of black powder that pressurize {@code volume} (m^3) to {@code pressure} (Pa gauge). */
	public static double blackPowderGrams(double pressure, double volume) {
		double psi = pressure / PSI;
		double in3 = volume / IN3;
		return psi * in3 / (R_BP * T_BP) * LBM_TO_G;
	}

	/** Pressure (Pa) produced by {@code grams} of black powder in {@code volume} (m^3). */
	public static double pressureFromGrams(double grams, double volume) {
		double lbm = grams / LBM_TO_G;
		double in3 = volume / IN3;
		return lbm * R_BP * T_BP / in3 * PSI;
	}

	/** Pressure acting on a bulkhead of diameter {@code diameter} that produces {@code force}. */
	public static double pressureForForce(double force, double diameter) {
		return force / Parachutes.circleArea(diameter);
	}

	/** Separating force from {@code pressure} acting on a bulkhead of diameter {@code diameter}. */
	public static double forceFromPressure(double pressure, double diameter) {
		return pressure * Parachutes.circleArea(diameter);
	}

	/** Number of pins needed to carry {@code holdForce} with {@code safetyFactor}. */
	public static int shearPinsToHold(double holdForce, double pinStrength, double safetyFactor) {
		return (int) Math.ceil(holdForce * safetyFactor / pinStrength - 1e-9);
	}
}
