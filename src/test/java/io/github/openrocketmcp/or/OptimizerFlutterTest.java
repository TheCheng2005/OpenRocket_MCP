package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The optimizer's fin flutter constraint. */
class OptimizerFlutterTest {
	static Optimizer.Point point(double flutter) {
		return new Optimizer.Point(new double[] { 0.1 }, 3000, 2.5, 4, 30, 1.2, 3, 9, flutter, null);
	}

	@Test
	void flutterBelowTheMarginIsAViolation() {
		Optimizer.Constraints c = new Optimizer.Constraints(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 1.5, null);
		assertEquals(0, Optimizer.violation(point(1.8), c), 1e-12);
		assertTrue(Optimizer.violation(point(1.2), c) > 0);
		assertTrue(Optimizer.violation(point(0.8), c) > Optimizer.violation(point(1.2), c), "worse flutter, larger violation");
		assertEquals(List.of("fin flutter margin 1.2 < 1.5"), Optimizer.violations(point(1.2), c));
	}

	@Test
	void unknownStiffnessOrNoConstraintIsNotAViolation() {
		Optimizer.Constraints c = new Optimizer.Constraints(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 1.5, null);
		assertEquals(0, Optimizer.violation(point(Double.NaN), c), 1e-12, "fin material of unknown shear modulus");
		Optimizer.Constraints none = new Optimizer.Constraints(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
		assertEquals(0, Optimizer.violation(point(0.5), none), 1e-12);
	}
}
