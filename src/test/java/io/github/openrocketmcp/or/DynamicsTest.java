package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.calc.Advanced;
import io.github.openrocketmcp.standards.Standards;

class DynamicsTest {

	@Test
	void dampingRatioMatchesHandCalculation() {
		// Two lifting surfaces: nose CNa 2 at x=0.1, fins CNa 8 at x=1.9; CG at 1.2 m; A = 0.008 m^2
		Dynamics.Aero a = new Dynamics.Aero(new double[] { 2, 8 }, new double[] { 0.1, 1.9 }, 0.008);
		double rho = 1.2, v = 150, cg = 1.2, il = 3.0, mdot = 0.8, xn = 2.0;
		double m1 = 2 * (0.1 - 1.2) + 8 * (1.9 - 1.2); // 3.4
		double m2 = 2 * 1.21 + 8 * 0.49; // 6.34
		double c1 = 0.5 * rho * v * v * 0.008 * m1;
		double c2 = 0.5 * rho * v * 0.008 * m2 + mdot * 0.8 * 0.8;
		assertEquals(c2 / (2 * Math.sqrt(c1 * il)), Dynamics.dampingRatio(a, rho, v, cg, il, mdot, xn), 1e-12);
		// Statically unstable: no damping ratio
		assertTrue(Double.isNaN(Dynamics.dampingRatio(a, rho, v, 1.8, il, 0, xn)));
	}

	@Test
	void componentCpsReproduceVehicleCp() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		Dynamics.Aero a = Dynamics.aero(fc, 0.3);
		double sum = 0, moment = 0;
		for (int i = 0; i < a.cna().length; i++) {
			sum += a.cna()[i];
			moment += a.cna()[i] * a.x()[i];
		}
		assertEquals(Analysis.cp(fc, 0.3), moment / sum, 1e-6);
	}

	@Test
	void ascentSamplesArePlausible() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.run(sim);
		List<Dynamics.Sample> s = Dynamics.ascent(sim, 30.48, 100);
		assertFalse(s.isEmpty());
		for (Dynamics.Sample x : s) {
			// Typical model/high-power rockets: 0.01 - 0.5
			assertTrue(x.dampingRatio() > 0.005 && x.dampingRatio() < 1, "zeta " + x.dampingRatio() + " at " + x.time());
			assertEquals(x.marginCalibers() * Analysis.maxDiameter(sim.getRocket().getSelectedConfiguration())
					/ sim.getRocket().getSelectedConfiguration().getLengthAerodynamic() * 100, x.marginPercentLength(), 1e-6);
		}
	}

	@Test
	void stagedVehicleUsesTheRemainingStackAfterSeparation() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.run(sim);
		double sep = Sims.eventTime(sim.getSimulatedData().getBranch(0), info.openrocket.core.simulation.FlightEvent.Type.STAGE_SEPARATION);
		FlightConfiguration sustainer = Dynamics.stack(sim.getRocket().getSelectedConfiguration(), 1);
		double pctPerCal = Analysis.maxDiameter(sustainer) / sustainer.getLengthAerodynamic() * 100;
		boolean checked = false;
		for (Dynamics.Sample x : Dynamics.ascent(sim, 30.48, 400)) {
			if (x.time() >= sep && !Double.isNaN(x.marginCalibers())) {
				assertEquals(x.marginCalibers() * pctPerCal, x.marginPercentLength(), 1e-6, "t=" + x.time());
				checked = true;
			}
		}
		assertTrue(checked);
	}

	@Test
	void advancedCalculations() {
		assertEquals(2 * 290e6 * 0.003 / 0.1524, Advanced.barlowBurst(290e6, 0.003, 0.1524), 1e-6);
		assertEquals(2 * 5e6 * 1.2, Advanced.requiredBurst(5e6, false, 1.2), 1e-9);
		assertEquals(20e6, Advanced.requiredBurst(5e6, true, 1.2), 1e-9);
		assertEquals(12e6 / 2 / 1.2, Advanced.maxMeop(12e6, false, 1.2), 1e-9);
		assertEquals(0.8 * 150, Advanced.aasi(8000, 10000, 150), 1e-12);
		assertEquals(150, Advanced.aasi(12000, 10000, 150), 1e-12);
	}
}
