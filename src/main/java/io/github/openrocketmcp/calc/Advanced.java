package io.github.openrocketmcp.calc;

/**
 * Launch Canada 2027 edict calculations for hybrid / liquid / tribrid programs: pressure-vessel margins and
 * probation levels.
 */
public final class Advanced {
	private Advanced() {
	}

	/** Barlow's formula (thin wall, conservative with the outer diameter): P = 2 S t / D. */
	public static double barlowBurst(double ultimateStrength, double wall, double outerDiameter) {
		return 2 * ultimateStrength * wall / outerDiameter;
	}

	/** Required design burst pressure: 2 x MEOP x knockdown (welded) for metal tanks, 4 x MEOP for COPVs/chambers. */
	public static double requiredBurst(double meop, boolean copv, double knockdown) {
		return copv ? 4 * meop : 2 * meop * Math.max(1, knockdown);
	}

	/** Highest MEOP (relief-valve set pressure) a vessel with this burst pressure may run at. */
	public static double maxMeop(double burst, boolean copv, double knockdown) {
		return copv ? burst / 4 : burst / 2 / Math.max(1, knockdown);
	}

	/** Altitude Adjusted Specific Impulse: min(1, actual / target) x static-fire Isp. */
	public static double aasi(double actualAltitude, double targetAltitude, double staticFireIsp) {
		return Math.min(1, actualAltitude / targetAltitude) * staticFireIsp;
	}

	/** Specific impulse from total impulse and propellant mass. */
	public static double isp(double totalImpulse, double propellantMass) {
		return totalImpulse / (propellantMass * Atmosphere.G0);
	}
}
