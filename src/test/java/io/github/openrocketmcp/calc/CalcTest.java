package io.github.openrocketmcp.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Checks against published ISA values and against the worked numbers in the team's recovery documents.
 */
class CalcTest {

	@Test
	void isaMatchesStandardTable() {
		assertEquals(1.225, Atmosphere.at(0).density(), 1e-3);
		// 12,000 ft (3658 m): ~0.849 kg/m^3 (team doc used 0.86)
		assertEquals(0.849, Atmosphere.at(3657.6).density(), 3e-3);
		assertEquals(0.3639, Atmosphere.at(11000).density(), 1e-3);
		assertEquals(0.08803, Atmosphere.at(20000).density(), 1e-3);
	}

	@Test
	void terminalVelocityMatchesShockForceDoc() {
		// Drogue: m = 50 kg, rho = 0.86, Cd = 2.2 (projected), A = 0.636 m^2 -> 28.5 m/s
		assertEquals(28.5, Parachutes.descentRate(50, 2.2 * 0.636, 0.86), 0.05);
		// Main: rho = 1.1, A = 7.07 m^2 -> 7.57 m/s
		assertEquals(7.57, Parachutes.descentRate(50, 2.2 * 7.07, 1.1), 0.02);
		double cdA = Parachutes.requiredCdA(50, 7.57, 1.1);
		assertEquals(2.2 * 7.07, cdA, 0.05);
	}

	@Test
	void infiniteMassOpeningForceMatchesShockForceDoc() {
		// 1/2 * 0.86 * 36^2 * 2.2 * 0.636 * 1.4 ~= 1100 N
		assertEquals(1091.6, OpeningShock.infiniteMass(0.86, 36, 2.2 * 0.636, 1.4), 0.5);
		// Main at 27.8 m/s -> 9253 N
		assertEquals(9253, OpeningShock.infiniteMass(1.1, 27.8, 2.2 * 7.07, 1.4), 5);
		// Horizontal airspeed matters: 30 m/s at apogee gives ~758 N from the drogue at t = 0
		assertEquals(758, OpeningShock.infiniteMass(0.86, 30, 2.2 * 0.636, 1.4), 1);
	}

	@Test
	void finiteMassInflationIsBelowInfiniteMassBoundForLightPayloads() {
		double cdA = 2.2 * 7.07;
		double rm = OpeningShock.massRatio(1.1, cdA, 50);
		assertEquals(1.35, rm, 0.01);
		OpeningShock.Inflation inf = OpeningShock.inflation(50, cdA, 0, 1.1, 27.8, Math.PI / 2, 3.0, 4, 1);
		double qCdA = OpeningShock.dynamicPressure(1.1, 27.8) * cdA;
		assertTrue(inf.peakForce() < qCdA, "finite-mass peak must be below the steady q*CdA");
		assertTrue(inf.peakForce() > 50 * 9.80665, "but above the steady-descent drag (weight)");
		assertEquals(inf.peakForce() / qCdA, inf.reductionFactor(), 1e-12);
	}

	@Test
	void heavyPayloadApproachesInfiniteMass() {
		// Very heavy payload: speed barely changes, peak -> q*CdA
		OpeningShock.Inflation inf = OpeningShock.inflation(1e6, 1.0, 0, 1.2, 30, 0, 1.0, 4, 1);
		assertEquals(1.0, inf.reductionFactor(), 0.01);
	}

	@Test
	void speedAfterApogeeMatchesClosedForm() {
		double m = 50, cdA = 0.75 * 0.0314, rho = 0.86;
		double vt = Math.sqrt(2 * m * Atmosphere.G0 / (rho * cdA));
		double expected = vt * Math.tanh(Atmosphere.G0 * 3 / vt);
		assertEquals(expected, OpeningShock.speedAfterApogee(m, cdA, rho, 0, 3), 1e-3);
		// with 30 m/s horizontal the airspeed is much higher than the vertical-only estimate
		assertTrue(OpeningShock.speedAfterApogee(m, cdA, rho, 30, 2.67) > 38);
	}

	@Test
	void blackPowderMatchesRuleOfThumb() {
		// 0.006 * D^2 * L grams at 15 psi (D, L in inches)
		double d = Units.toSi("4 in", Dim.LENGTH), l = Units.toSi("10 in", Dim.LENGTH);
		double grams = Charges.blackPowderGrams(Units.toSi("15 psi", Dim.PRESSURE), Packing.cylinderVolume(d, l));
		assertEquals(0.00607 * 16 * 10 * Math.PI / 4 / 0.7854, grams, 0.01);
		double back = Charges.pressureFromGrams(grams, Packing.cylinderVolume(d, l));
		assertEquals(Units.toSi("15 psi", Dim.PRESSURE), back, 1);
	}

	@Test
	void shearPinsFromShockForceDoc() {
		// Four 4-40 pins hold 560 N, i.e. 140 N each. 490 N with SF 2 needs 7 pins.
		assertEquals(4, Charges.shearPinsToHold(560, 140, 1));
		assertEquals(7, Charges.shearPinsToHold(490, 140, 2));
	}

	@Test
	void bayLengthsMatchRecoveryVolumeDoc() {
		double in = 0.0254, in3 = Math.pow(in, 3);
		// Drogue bay: 112 in^3 in a 4.343 in ID tube -> 7.57 in
		assertEquals(7.57, Packing.lengthFor(112 * in3, 4.343 * in) / in, 0.01);
		// Main: (75 + 56.25) in^3 in a 4.34 in ID tube -> 8.87 in
		assertEquals(8.87, Packing.lengthFor(131.25 * in3, 4.34 * in) / in, 0.01);
		// Cord: 3/4 x 1/4 x 252 in = 47.25 in^3
		assertEquals(47.25, Packing.cordVolume(0.75 * in, 0.25 * in, 252 * in) / in3, 1e-9);
	}
}
