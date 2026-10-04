package io.github.openrocketmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import io.github.openrocketmcp.units.Dim;

class LimitsTest {
	static Args args(String json) {
		return new Args(JsonParser.parseString(json).getAsJsonObject());
	}

	static String refused(Runnable r) {
		return assertThrows(ToolException.class, r::run).getMessage();
	}

	@Test
	void nonPhysicalQuantitiesAreRefusedWithTheirName() {
		assertTrue(refused(() -> args("{\"mass\": -2}").qty("mass", Dim.MASS)).contains("'mass'"));
		assertTrue(refused(() -> args("{\"airDensity\": 0}").qty("airDensity", Dim.DENSITY)).contains("physical range"));
		assertTrue(refused(() -> args("{\"windSpeed\": \"1e30 m/s\"}").qty("windSpeed", Dim.VELOCITY)).contains("windSpeed"));
		assertTrue(refused(() -> args("{\"temperature\": \"-300 C\"}").qty("temperature", Dim.TEMPERATURE)).contains("temperature"));
		assertTrue(refused(() -> args("{\"cd\": -0.8}").num("cd")).contains("'cd'"));
		assertTrue(refused(() -> args("{\"latitude\": 120}").num("latitude")).contains("latitude"));
		assertTrue(refused(() -> args("{\"launchAngle\": \"75 deg\"}").qty("launchAngle", Dim.ANGLE)).contains("launchAngle"));
	}

	@Test
	void unnamedArgumentsGetTheirDimensionsDefault() {
		assertFalse(Limits.hasRule("sledMass"));
		assertTrue(refused(() -> args("{\"sledMass\": -1}").qty("sledMass", Dim.MASS)).contains("sledMass"));
		assertEquals(0, args("{\"sledMass\": 0}").qty("sledMass", Dim.MASS));
		// Offsets on the ground may be negative (a station west of the pad).
		assertEquals(-500, args("{\"groundStationEast\": -500}").qty("groundStationEast", Dim.DISTANCE));
		// A motor may be recessed in its mount.
		assertEquals(-0.01, args("{\"overhang\": \"-10 mm\"}").qty("overhang", Dim.LENGTH), 1e-12);
	}

	@Test
	void ordinaryValuesPass() {
		assertEquals(0.8, args("{\"cd\": 0.8}").num("cd"));
		assertEquals(6.096, args("{\"targetDescentRate\": \"20 ft/s\"}").qty("targetDescentRate", Dim.VELOCITY), 1e-3);
		assertEquals(288.15, args("{\"temperature\": \"15 C\"}").qty("temperature", Dim.TEMPERATURE), 1e-9);
		assertEquals(5, args("{\"runs\": 5}").integer("runs", 1));
	}

	@Test
	void countsAndSwitchesAreWhatTheySayTheyAre() {
		assertTrue(refused(() -> args("{\"count\": 2.5}").integer("count", 1)).contains("whole number"));
		assertTrue(refused(() -> args("{\"count\": 0}").integer("count", 1)).contains("count"));
		assertTrue(refused(() -> args("{\"apply\": \"maybe\"}").bool("apply", false)).contains("true or false"));
		assertTrue(args("{\"apply\": \"yes\"}").bool("apply", false));
		assertTrue(refused(() -> args("{\"mass\": [1]}").qty("mass", Dim.MASS)).contains("quantity with units"));
	}
}
