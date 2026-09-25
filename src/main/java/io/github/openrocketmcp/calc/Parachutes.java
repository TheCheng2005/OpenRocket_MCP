package io.github.openrocketmcp.calc;

/**
 * Steady-descent parachute relations. CdA is always drag coefficient times the reference area that the Cd
 * was measured against (nominal canopy area for OpenRocket and most vendors; projected area for
 * some, e.g. Fruity Chutes' published Cd of 2.2). Never mix the two.
 */
public final class Parachutes {
	private Parachutes() {
	}

	/** Terminal (steady) descent rate, v = sqrt(2mg / (rho CdA)). */
	public static double descentRate(double mass, double cdA, double density) {
		return Math.sqrt(2 * mass * Atmosphere.G0 / (density * cdA));
	}

	/** CdA needed to descend at {@code velocity}. */
	public static double requiredCdA(double mass, double velocity, double density) {
		return 2 * mass * Atmosphere.G0 / (density * velocity * velocity);
	}

	/** Circular canopy diameter whose area gives {@code cdA} at drag coefficient {@code cd}. */
	public static double diameterFor(double cdA, double cd) {
		return Math.sqrt(4 * cdA / (Math.PI * cd));
	}

	public static double circleArea(double diameter) {
		return Math.PI * diameter * diameter / 4;
	}

	/** Kinetic energy at touchdown, 1/2 m v^2. */
	public static double kineticEnergy(double mass, double velocity) {
		return 0.5 * mass * velocity * velocity;
	}

	/** Mass that may land at {@code velocity} without exceeding {@code energy}. */
	public static double maxMassForEnergy(double energy, double velocity) {
		return 2 * energy / (velocity * velocity);
	}
}
