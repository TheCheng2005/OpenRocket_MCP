package io.github.openrocketmcp.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UnitsTest {

	@AfterEach
	void reset() {
		Units.setSystem(UnitSystem.BOTH);
	}

	@Test
	void parsesCommonRocketryUnits() {
		assertEquals(6.096, Units.toSi("20 ft/s", Dim.VELOCITY), 1e-9);
		assertEquals(0.11031, Units.toSi("4.343in", Dim.LENGTH), 1e-5);
		assertEquals(103421.4, Units.toSi("15 psi", Dim.PRESSURE), 0.1);
		assertEquals(101.686, Units.toSi("75 ft-lbf", Dim.ENERGY), 1e-3);
		assertEquals(101.686, Units.toSi("75 ft·lbf", Dim.ENERGY), 1e-3);
		assertEquals(0.0025, Units.toSi("2.5 g", Dim.CHARGE_MASS), 1e-12);
		assertEquals(1.835e-3, Units.toSi("112 in³", Dim.VOLUME), 1e-6);
		assertEquals(Math.toRadians(6), Units.toSi("6 deg", Dim.ANGLE), 1e-12);
		assertEquals(298.15, Units.toSi("25 C", Dim.TEMPERATURE), 1e-9);
		assertEquals(457.2, Units.toSi("1500 ft", Dim.DISTANCE), 1e-9);
		assertEquals(8.3333, Units.toSi("30 km/h", Dim.VELOCITY), 1e-4);
	}

	@Test
	void bareNumbersAreSi() {
		assertEquals(12.5, Units.toSi(12.5, Dim.MASS));
		assertEquals(12.5, Units.toSi("12.5", Dim.MASS));
	}

	@Test
	void rejectsWrongDimension() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Units.toSi("20 ft/s", Dim.LENGTH));
		assertEquals(true, e.getMessage().contains("velocity"));
		assertThrows(IllegalArgumentException.class, () -> Units.toSi("20 furlongs", Dim.LENGTH));
	}

	@Test
	void formatsInActiveSystem() {
		Units.setSystem(UnitSystem.IMPERIAL);
		assertEquals("100 ft/s", Units.fmt(30.48, Dim.VELOCITY));
		Units.setSystem(UnitSystem.METRIC);
		assertEquals("30.48 m/s", Units.fmt(30.48, Dim.VELOCITY));
		Units.setSystem(UnitSystem.BOTH);
		assertEquals("30.48 m/s (100 ft/s)", Units.fmt(30.48, Dim.VELOCITY));
		assertEquals("2.5 g", Units.fmt(0.0025, Dim.CHARGE_MASS));
	}
}
