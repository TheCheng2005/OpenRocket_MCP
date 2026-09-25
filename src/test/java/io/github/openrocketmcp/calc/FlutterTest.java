package io.github.openrocketmcp.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlutterTest {
	// Trapezoid: span 100 mm, root 150 mm, tip 50 mm, 3 mm thick; area = (0.15 + 0.05) / 2 x 0.1 = 0.01 m^2
	static final Flutter.Fin FIN = new Flutter.Fin(0.1, 0.01, 0.15, 0.05, 0.003);
	static final double G10 = 2.9e9, A = 340.29, P = 101325;

	@Test
	void matchesNacaFormWithPsiUnits() {
		// NACA TN 4197 as printed: G in psi, pressure ratio P/P0 with P0 = 14.696 psi, constant 39.3.
		double gPsi = G10 / 6894.757293168;
		double ar = 1.0, lambda = 1.0 / 3, tc = 0.02;
		double denom = 39.3 * Math.pow(ar, 3) / (Math.pow(tc, 3) * (ar + 2)) * (lambda + 1) / 2 * (P / 101325);
		double expected = A * Math.sqrt(gPsi / denom);
		assertEquals(expected, Flutter.velocity(FIN, G10, A, P, Flutter.NACA_CONSTANT), expected * 1e-5); // 14.696 vs 14.6959 psi
		assertEquals(2.674, Flutter.NACA_CONSTANT, 1e-3);
	}

	@Test
	void peakOfFlight291ConstantOverestimatesBySqrt2() {
		double corrected = Flutter.velocity(FIN, G10, A, P, Flutter.NACA_CONSTANT);
		double pof291 = Flutter.velocity(FIN, G10, A, P, 1.337);
		assertEquals(Math.sqrt(2), pof291 / corrected, 1e-3);
	}

	@Test
	void scalingLaws() {
		double v = Flutter.velocity(FIN, G10, A, P, Flutter.NACA_CONSTANT);
		Flutter.Fin thick = new Flutter.Fin(0.1, 0.01, 0.15, 0.05, 0.006);
		assertEquals(Math.pow(2, 1.5), Flutter.velocity(thick, G10, A, P, Flutter.NACA_CONSTANT) / v, 1e-9, "Vf ~ t^1.5");
		assertEquals(2, Flutter.velocity(FIN, 4 * G10, A, P, Flutter.NACA_CONSTANT) / v, 1e-9, "Vf ~ sqrt(G)");
		assertEquals(Math.sqrt(2), Flutter.velocity(FIN, G10, A, P / 2, Flutter.NACA_CONSTANT) / v, 1e-9, "Vf ~ 1/sqrt(P)");
		// Flutter speed rises with altitude (pressure falls faster than the speed of sound)
		var alt = Atmosphere.at(9000);
		double a9 = Math.sqrt(1.4 * Atmosphere.R_AIR * alt.temperature());
		assertTrue(Flutter.velocity(FIN, G10, a9, alt.pressure(), Flutter.NACA_CONSTANT) > v);
		// Longer span (higher aspect ratio) flutters earlier
		Flutter.Fin tall = Flutter.Fin.equivalent(0.2, 0.02, 0.15, 0.003);
		assertTrue(Flutter.velocity(tall, G10, A, P, Flutter.NACA_CONSTANT) < v);
	}

	@Test
	void inversesRoundTrip() {
		double target = 600;
		double t = Flutter.requiredThickness(FIN, G10, target, A, P, Flutter.NACA_CONSTANT);
		Flutter.Fin f = new Flutter.Fin(FIN.span(), FIN.area(), FIN.rootChord(), FIN.tipChord(), t);
		assertEquals(target, Flutter.velocity(f, G10, A, P, Flutter.NACA_CONSTANT), 1e-6);
		double g = Flutter.requiredShearModulus(FIN, target, A, P, Flutter.NACA_CONSTANT);
		assertEquals(target, Flutter.velocity(FIN, g, A, P, Flutter.NACA_CONSTANT), 1e-6);
	}

	@Test
	void equivalentTrapezoidRecoversTipChord() {
		Flutter.Fin e = Flutter.Fin.equivalent(0.1, 0.01, 0.15, 0.003);
		assertEquals(0.05, e.tipChord(), 1e-12);
		assertEquals(1.0, e.aspectRatio(), 1e-12);
		// Triangular (delta) fin: zero tip chord, never negative
		assertEquals(0, Flutter.Fin.equivalent(0.1, 0.005, 0.15, 0.003).tipChord(), 1e-12);
		assertEquals(0, Flutter.Fin.equivalent(0.1, 0.004, 0.15, 0.003).tipChord(), 1e-12);
	}

	@Test
	void degenerateInputsGiveNaN() {
		assertTrue(Double.isNaN(Flutter.velocity(new Flutter.Fin(0.1, 0.01, 0.15, 0.05, 0), G10, A, P, 2.674)));
		assertTrue(Double.isNaN(Flutter.velocity(FIN, 0, A, P, 2.674)));
		assertTrue(Double.isNaN(Flutter.velocity(FIN, G10, A, 0, 2.674)));
	}
}
