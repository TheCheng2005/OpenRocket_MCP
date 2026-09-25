package io.github.openrocketmcp.calc;

/**
 * Parachute opening-load estimates.
 *
 * <p>Two methods, both from the Navy Parachute Recovery Systems Design Manual (Knacke, NWC TP 6575,
 * DTIC ADA247666):
 * <ul>
 * <li><b>Infinite mass</b>: F = Cx * q * CdA. Cx is the opening-force coefficient for a payload so heavy that it
 * does not slow down while the canopy fills. Conservative for typical amateur rockets.</li>
 * <li><b>Finite mass (inflation simulation)</b>: integrates the vehicle's deceleration while the canopy's CdA
 * grows over the fill time t_f = n * D0 / v0 (n = canopy fill constant). The vehicle slows while the canopy
 * opens, so the peak is lower. Whether that matters is indicated by the mass ratio Rm = rho * CdA^1.5 / m:
 * small Rm behaves like the infinite-mass case, large Rm (light payload, big canopy) does not.</li>
 * </ul>
 * Both are estimates. Calibrate the fill constant and inflation exponent from your own test data.
 */
public final class OpeningShock {
	private OpeningShock() {
	}

	/** Dynamic pressure q = 1/2 rho v^2. */
	public static double dynamicPressure(double density, double velocity) {
		return 0.5 * density * velocity * velocity;
	}

	/** Infinite-mass opening force, F = Cx * 1/2 rho v^2 * CdA. */
	public static double infiniteMass(double density, double velocity, double cdA, double cx) {
		return cx * dynamicPressure(density, velocity) * cdA;
	}

	/** Knacke mass ratio Rm = rho * CdA^(3/2) / m. */
	public static double massRatio(double density, double cdA, double mass) {
		return density * Math.pow(cdA, 1.5) / mass;
	}

	/**
	 * Result of an inflation simulation.
	 *
	 * @param peakForce       peak drag of the opening canopy (N): the load on its harness
	 * @param peakDeceleration peak deceleration of the vehicle (m/s^2)
	 * @param timeOfPeak      time after line stretch (s)
	 * @param fillTime        canopy fill time used (s)
	 * @param velocityAtPeak  airspeed at the peak (m/s)
	 * @param reductionFactor peakForce / (q0 * CdA), i.e. the effective opening-load factor
	 */
	public record Inflation(double peakForce, double peakDeceleration, double timeOfPeak, double fillTime,
			double velocityAtPeak, double reductionFactor) {
	}

	/**
	 * Simulates canopy inflation for a point mass in 2D.
	 *
	 * @param mass            mass decelerated by the canopy (kg)
	 * @param cdA             full-open CdA of the opening canopy (m^2)
	 * @param otherCdA        CdA already acting (open drogue, body), included in the dynamics (m^2)
	 * @param density         air density (kg/m^3)
	 * @param v0              airspeed at line stretch (m/s)
	 * @param pathAngle       flight-path angle below horizontal (rad): 0 = horizontal, pi/2 = straight down
	 * @param nominalDiameter canopy nominal diameter D0 (m)
	 * @param fillConstant    canopy fill constant n (fill distance = n * D0)
	 * @param exponent        inflation-curve exponent j: CdA(t) = CdA * (t/t_f)^j. 1 is conservative.
	 */
	public static Inflation inflation(double mass, double cdA, double otherCdA, double density, double v0,
			double pathAngle, double nominalDiameter, double fillConstant, double exponent) {
		double fillTime = fillConstant * nominalDiameter / v0;
		double vx = v0 * Math.cos(pathAngle);
		double vz = -v0 * Math.sin(pathAngle);
		double t = 0;
		int steps = 4000;
		double dt = 2 * fillTime / steps;
		double peakF = 0, peakA = 0, tPeak = 0, vPeak = v0;
		for (int i = 0; i <= steps; i++) {
			double v = Math.hypot(vx, vz);
			double canopy = cdA * Math.pow(Math.min(1, t / fillTime), exponent);
			double f = dynamicPressure(density, v) * canopy;
			double aDrag = dynamicPressure(density, v) * (canopy + otherCdA) / mass;
			if (f > peakF) {
				peakF = f;
				tPeak = t;
				vPeak = v;
			}
			peakA = Math.max(peakA, aDrag);
			// RK2 (midpoint) step
			double[] k1 = accel(vx, vz, t, mass, cdA, otherCdA, density, fillTime, exponent);
			double mx = vx + 0.5 * dt * k1[0];
			double mz = vz + 0.5 * dt * k1[1];
			double[] k2 = accel(mx, mz, t + 0.5 * dt, mass, cdA, otherCdA, density, fillTime, exponent);
			vx += dt * k2[0];
			vz += dt * k2[1];
			t += dt;
		}
		return new Inflation(peakF, peakA, tPeak, fillTime, vPeak, peakF / (dynamicPressure(density, v0) * cdA));
	}

	private static double[] accel(double vx, double vz, double t, double mass, double cdA, double otherCdA,
			double density, double fillTime, double exponent) {
		double v = Math.hypot(vx, vz);
		double canopy = cdA * Math.pow(Math.min(1, t / fillTime), exponent);
		double k = 0.5 * density * v * (canopy + otherCdA) / mass;
		return new double[] { -k * vx, -k * vz - Atmosphere.G0 };
	}

	/**
	 * Airspeed of a body falling from apogee with drag, starting with horizontal airspeed {@code vx0} and zero
	 * vertical speed. Exact for the vertical-only case: v = v_t tanh(g t / v_t).
	 */
	public static double speedAfterApogee(double mass, double bodyCdA, double density, double vx0, double time) {
		double vx = vx0, vz = 0, t = 0;
		int steps = Math.max(200, (int) (time / 0.001));
		double dt = time / steps;
		for (int i = 0; i < steps; i++) {
			double v = Math.hypot(vx, vz);
			double k = 0.5 * density * v * bodyCdA / mass;
			double ax1 = -k * vx, az1 = -k * vz - Atmosphere.G0;
			double mx = vx + 0.5 * dt * ax1, mz = vz + 0.5 * dt * az1;
			double vm = Math.hypot(mx, mz);
			double km = 0.5 * density * vm * bodyCdA / mass;
			vx += dt * (-km * mx);
			vz += dt * (-km * mz - Atmosphere.G0);
			t += dt;
		}
		return Math.hypot(vx, vz);
	}
}
