package io.github.openrocketmcp.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Property checks over random inputs: scaling laws and inverse pairs that any correct implementation satisfies. */
class PhysicsInvariantsTest {
	static final Random R = new Random(42);

	static double between(double lo, double hi) {
		return lo + (hi - lo) * R.nextDouble();
	}

	@RepeatedTest(25)
	void descentRateAndRequiredAreaAreInverses() {
		double m = between(0.5, 60), v = between(3, 40), rho = between(0.7, 1.3);
		double cda = Parachutes.requiredCdA(m, v, rho);
		assertEquals(v, Parachutes.descentRate(m, cda, rho), v * 1e-12);
		// v ~ sqrt(m): quadrupling the mass doubles the descent rate
		assertEquals(2 * v, Parachutes.descentRate(4 * m, cda, rho), v * 1e-9);
		// diameter round trip
		double cd = between(0.6, 2.2);
		assertEquals(cda, cd * Parachutes.circleArea(Parachutes.diameterFor(cda, cd)), cda * 1e-12);
		// landing energy of the maximum mass equals the energy limit
		double e = between(10, 200);
		assertEquals(e, Parachutes.kineticEnergy(Parachutes.maxMassForEnergy(e, v), v), e * 1e-12);
	}

	@RepeatedTest(25)
	void blackPowderScalesWithVolumeAndPressure() {
		double p = between(5, 25) * 6894.757, vol = between(1e-4, 5e-3);
		double g = Charges.blackPowderGrams(p, vol);
		assertEquals(2 * g, Charges.blackPowderGrams(p, 2 * vol), g * 1e-9);
		assertEquals(2 * g, Charges.blackPowderGrams(2 * p, vol), g * 1e-9);
		assertEquals(p, Charges.pressureFromGrams(g, vol), p * 1e-9);
		double d = between(0.05, 0.2);
		assertEquals(p, Charges.pressureForForce(Charges.forceFromPressure(p, d), d), p * 1e-9);
	}

	@RepeatedTest(25)
	void shearPinCountHoldsTheLoad() {
		double f = between(50, 3000), pin = between(50, 400), sf = between(1, 3);
		int n = Charges.shearPinsToHold(f, pin, sf);
		assertTrue(n * pin >= f * sf * (1 - 1e-9), "pins hold");
		assertTrue((n - 1) * pin < f * sf, "no more pins than needed");
	}

	@RepeatedTest(25)
	void finiteMassLoadNeverExceedsInfiniteMassMuch() {
		double m = between(1, 40), rho = between(0.8, 1.2), v = between(15, 60), d = between(0.5, 3);
		double cda = 0.8 * Parachutes.circleArea(d);
		double inf = OpeningShock.infiniteMass(rho, v, cda, 1.4);
		assertTrue(inf > 0);
		assertEquals(OpeningShock.dynamicPressure(rho, v) * cda * 1.4, inf, inf * 1e-12);
		double rm = OpeningShock.massRatio(rho, cda, m);
		assertTrue(rm > 0);
		assertEquals(rho * Math.pow(cda, 1.5) / m, rm, rm * 1e-12);
	}

	@Test
	void atmosphereIsMonotonic() {
		double lastP = Double.MAX_VALUE, lastRho = Double.MAX_VALUE;
		for (int h = 0; h <= 20000; h += 250) {
			Atmosphere.State s = Atmosphere.at(h);
			assertTrue(s.pressure() < lastP && s.density() < lastRho, "h=" + h);
			assertEquals(s.density(), s.pressure() / (Atmosphere.R_AIR * s.temperature()), 1e-12);
			lastP = s.pressure();
			lastRho = s.density();
		}
		// Hot day: same pressure, lower density
		assertEquals(Atmosphere.at(1000).pressure(), Atmosphere.at(1000, 15).pressure(), 1e-9);
		assertTrue(Atmosphere.at(1000, 15).density() < Atmosphere.at(1000).density());
	}

	@Test
	void packingVolumes() {
		assertEquals(0.75 * 0.25 * 252, Packing.cordVolume(0.75, 0.25, 252), 1e-12); // team doc: 47.25 in^3 for 21 ft
		double v = Packing.cylinderVolume(0.1, 0.3);
		assertEquals(0.3, Packing.lengthFor(v, 0.1), 1e-12);
	}
}
