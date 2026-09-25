package io.github.openrocketmcp.calc;

/**
 * Fin flutter screening estimate from NACA TN 4197 (D. J. Martin, 1958), the relation behind every hobby flutter
 * calculator:
 *
 * <pre>
 *   Vf = a sqrt( G / ( K AR^3 (lambda + 1) P / (2 (AR + 2) (t/c)^3) ) )      K = 39.3 / 14.696 = 2.674
 * </pre>
 *
 * with a the speed of sound, G the fin material's shear modulus, P the static pressure (G and P in the same units),
 * AR = span^2 / planform area, lambda = tip / root chord and t/c the thickness over the root chord. TN 4197 writes the
 * pressure term as P/P0 with G in psi and P0 = 14.696 psi, so K = 2.674 in consistent units. Apogee "Peak of Flight"
 * #291/#411 printed K = 1.337, which overestimates the flutter speed by sqrt(2); #615 (J. K. Bennett, 2023) corrects
 * it. The relation assumes a thin, solid, isotropic, cantilevered plate: treat it as a screening check, not a
 * clearance, for composites, sandwich fins, tip-to-tip layups or thin-wall mounts.
 */
public final class Flutter {
	private Flutter() {
	}

	public static final double NACA_CONSTANT = 39.3 / 14.696;

	/** Fin geometry used by the relation. */
	public record Fin(double span, double area, double rootChord, double tipChord, double thickness) {
		public double aspectRatio() {
			return span * span / area;
		}

		public double taper() {
			return rootChord <= 0 ? 0 : Math.max(0, tipChord / rootChord);
		}

		public double thicknessRatio() {
			return thickness / rootChord;
		}

		/** Equivalent trapezoid from span, planform area and root chord (tip chord from the area). */
		public static Fin equivalent(double span, double area, double rootChord, double thickness) {
			double tip = span > 0 ? Math.max(0, 2 * area / span - rootChord) : 0;
			return new Fin(span, area, rootChord, tip, thickness);
		}
	}

	/** Flutter velocity (m/s) at the given speed of sound and static pressure. */
	public static double velocity(Fin f, double shearModulus, double speedOfSound, double pressure, double constant) {
		double ar = f.aspectRatio();
		double tc = f.thicknessRatio();
		if (!(ar > 0) || !(tc > 0) || !(pressure > 0) || !(shearModulus > 0)) {
			return Double.NaN;
		}
		double denom = constant * ar * ar * ar * (f.taper() + 1) * pressure / (2 * (ar + 2) * tc * tc * tc);
		return speedOfSound * Math.sqrt(shearModulus / denom);
	}

	/** Shear modulus that gives flutter velocity {@code target}: the inverse of {@link #velocity}. */
	public static double requiredShearModulus(Fin f, double target, double speedOfSound, double pressure, double constant) {
		double ar = f.aspectRatio(), tc = f.thicknessRatio();
		double denom = constant * ar * ar * ar * (f.taper() + 1) * pressure / (2 * (ar + 2) * tc * tc * tc);
		double r = target / speedOfSound;
		return r * r * denom;
	}

	/** Thickness that gives flutter velocity {@code target} with the other dimensions unchanged. */
	public static double requiredThickness(Fin f, double shearModulus, double target, double speedOfSound, double pressure,
			double constant) {
		// Vf is proportional to t^1.5.
		double v = velocity(f, shearModulus, speedOfSound, pressure, constant);
		return f.thickness() * Math.pow(target / v, 2.0 / 3.0);
	}
}
