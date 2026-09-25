package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.rocketcomponent.Rocket;
import io.github.openrocketmcp.standards.Standards;

class MonteCarloTest {
	static Simulation base(Designs.Design d) {
		return Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
	}

	@Test
	void ellipseAxisFollowsTheSpread() {
		// Landings along the north-east diagonal: major axis bearing 45 deg, minor axis ~0
		List<double[]> pts = new ArrayList<>();
		for (int i = -10; i <= 10; i++) {
			pts.add(new double[] { 100 + 10 * i, 50 + 10 * i });
		}
		Map<String, Object> m = MonteCarlo.landing(pts);
		assertTrue(m.get("ellipse2Sigma").toString().contains("bearing 45 deg"), m.toString());
		assertTrue(m.get("meanPoint").toString().startsWith("100 m"), m.toString());
		// North-south spread: bearing 0 (or 180)
		pts.clear();
		for (int i = -10; i <= 10; i++) {
			pts.add(new double[] { 0, 10 * i });
		}
		assertTrue(MonteCarlo.landing(pts).get("ellipse2Sigma").toString().contains("bearing 0 deg"));
	}

	@Test
	void percentilesAndCorrelation() {
		double[] v = { 1, 2, 3, 4, 5 };
		assertEquals(3, MonteCarlo.pct(v, 50), 1e-12);
		assertEquals(1.2, MonteCarlo.pct(v, 5), 1e-12);
		List<double[]> in = new ArrayList<>(), out = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			in.add(new double[] { i, 1 });
			out.add(new double[] { 3 * i + 2, -i });
		}
		assertEquals(1, MonteCarlo.correlation(in, out, 0, 0), 1e-12);
		assertEquals(-1, MonteCarlo.correlation(in, out, 0, 1), 1e-12);
		assertTrue(Double.isNaN(MonteCarlo.correlation(in, out, 1, 0)), "constant input has no correlation");
	}

	@Test
	void massScalingIsLinearAndExcludesMotors() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		double[] launch = new double[3], burnout = new double[3];
		double[] f = { 1, 1.05, 1.1 };
		for (int i = 0; i < 3; i++) {
			Rocket r = d.doc.getRocket().copyWithOriginalID();
			MonteCarlo.scaleMass(r, f[i]);
			var fc = r.getSelectedConfiguration();
			launch[i] = MassCalculator.calculateLaunch(fc).getMass();
			burnout[i] = MassCalculator.calculateBurnout(fc).getMass();
		}
		double d1 = launch[1] - launch[0], d2 = launch[2] - launch[0];
		assertTrue(d1 > 0);
		assertEquals(2 * d1, d2, d1 * 1e-6, "linear in the factor");
		assertEquals(d1, burnout[1] - burnout[0], d1 * 1e-6, "propellant is not scaled");
		assertEquals(0.05 * burnout[0], d1, 0.05 * burnout[0] * 0.25, "roughly 5% of the dry vehicle (motor casing excluded)");
	}

	@Test
	void thrustAndDragListenersChangeTheFlight() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation b = base(d);
		List<Simulation> sims = List.of(Variants.of(b, d.doc, null, null),
				Variants.listen(Variants.of(b, d.doc, null, null), new MonteCarlo.Scaling(1, 1.1)),
				Variants.listen(Variants.of(b, d.doc, null, null), new MonteCarlo.Scaling(1.3, 1)));
		List<Variants.Run> runs = Variants.runAll(sims);
		double nominal = runs.get(0).sim().getSimulatedData().getMaxAltitude();
		double hot = runs.get(1).sim().getSimulatedData().getMaxAltitude();
		double draggy = runs.get(2).sim().getSimulatedData().getMaxAltitude();
		assertTrue(hot > nominal * 1.05, nominal + " -> " + hot);
		assertTrue(draggy < nominal * 0.97, nominal + " -> " + draggy);
		double railNominal = runs.get(0).sim().getSimulatedData().getLaunchRodVelocity();
		assertTrue(runs.get(1).sim().getSimulatedData().getLaunchRodVelocity() > railNominal);
	}

	@Test
	void repeatableForASeedAndDriversIdentifyTheCause() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Standards std = Standards.defaults();
		// Only thrust varies: it must dominate the apogee spread.
		MonteCarlo.Settings s = new MonteCarlo.Settings(16, 0, 0, 0, 0, Double.NaN, 0, 0, Double.NaN, 7, 0, 0, 0.1, 0);
		Map<String, Object> a = MonteCarlo.run(base(d), d.doc, s, std);
		Map<String, Object> b = MonteCarlo.run(base(d), d.doc, s, std);
		assertEquals(a.toString(), b.toString(), "same seed, same answer");
		@SuppressWarnings("unchecked")
		Map<String, Object> apogee = (Map<String, Object>) ((Map<String, Object>) a.get("drivers")).get("apogee");
		assertTrue(Double.parseDouble(apogee.get("motorThrust").toString()) > 0.9, apogee.toString());
		assertTrue(a.get("conditions").toString().contains("motorThrust"));
		MonteCarlo.Settings other = new MonteCarlo.Settings(16, 0, 0, 0, 0, Double.NaN, 0, 0, Double.NaN, 8, 0, 0, 0.1, 0);
		assertNotEquals(a.get("apogee").toString(), MonteCarlo.run(base(d), d.doc, other, std).get("apogee").toString());
	}

	@Test
	void parachuteCdVariationSpreadsDescentRates() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		MonteCarlo.Settings s = new MonteCarlo.Settings(12, 0, 0, 0, 0, Double.NaN, 0, 0, Double.NaN, 3, 0, 0, 0, 0.15);
		Map<String, Object> out = MonteCarlo.run(base(d), d.doc, s, Standards.defaults());
		String deps = out.get("deployments").toString();
		assertTrue(deps.contains("steadyDescentRateRange"), deps);
		// every chute's range is non-degenerate: "x to y" with x != y
		for (Object o : (List<?>) out.get("deployments")) {
			String range = ((Map<?, ?>) o).get("steadyDescentRateRange").toString();
			String[] parts = range.split(" to ");
			assertNotEquals(parts[0], parts[1], range);
		}
	}
}
