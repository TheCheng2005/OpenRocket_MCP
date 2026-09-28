package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.units.Dim;

/** Which unit a component property takes, from its name. */
class ComponentUnitsTest {
	@Test
	void propertyNamesMapToTheRightUnits() {
		assertEquals(Dim.LENGTH, Components.dimOf("instanceSeparation"), "rail button spacing (contains the letters 'ratio')");
		assertEquals(Dim.LENGTH, Components.dimOf("aftShoulderLength"));
		assertEquals(Dim.LENGTH, Components.dimOf("sweep"), "trapezoid fin sweep is a length");
		assertEquals(Dim.ANGLE, Components.dimOf("sweepAngle"));
		assertEquals(Dim.ANGLE, Components.dimOf("cantAngle"));
		assertEquals(Dim.MASS, Components.dimOf("componentMass"));
		assertEquals(Dim.DIMENSIONLESS, Components.dimOf("finCount"));
		assertEquals(Dim.DIMENSIONLESS, Components.dimOf("cd"));
		assertEquals(Dim.DIMENSIONLESS, Components.dimOf("lengthRatio"), "a ratio suffix stays dimensionless");
		assertEquals(Dim.DISTANCE, Components.dimOf("deployAltitude"));
		assertEquals(Dim.TIME, Components.dimOf("deployDelay"));
	}
}
