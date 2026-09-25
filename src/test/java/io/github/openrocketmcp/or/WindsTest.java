package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.standards.Standards;

class WindsTest {
	static Simulation sim(Designs.Design d) {
		return Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
	}

	@Test
	void powerLawShape() {
		List<Winds.Level> l = Winds.powerLaw(5, 1.0, 1.0 / 7, 3000, 0.1);
		assertEquals(0, l.get(0).altitude(), 0);
		assertEquals(5, l.get(0).speed(), 1e-12, "constant below 10 m");
		for (Winds.Level x : l) {
			if (x.altitude() >= 10) {
				assertEquals(5 * Math.pow(x.altitude() / 10, 1.0 / 7), x.speed(), 1e-9);
			}
			assertEquals(0.1 * x.speed(), x.sd(), 1e-12);
			assertEquals(1.0, x.direction(), 0);
		}
		assertEquals(3000, l.get(l.size() - 1).altitude(), 0, "stops at the top level");
	}

	@Test
	void profileSurvivesRunsSummariesAndIsRepeatable() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation s = sim(d);
		Winds.setProfile(s.getOptions(), Winds.powerLaw(6, Math.PI / 2, 1.0 / 7, 3000, 0.1), true);
		Sims.run(s);
		double a1 = s.getSimulatedData().getMaxAltitude();
		Sims.summarize(s); // OpenRocket's wind getters select the average model; summaries must not
		assertTrue(Winds.isMultiLevel(s.getOptions()));
		Sims.run(s);
		assertEquals(a1, s.getSimulatedData().getMaxAltitude(), 1e-9, "same seed, same turbulence per level");
		assertTrue(Winds.isMultiLevel(s.getOptions()));
	}

	@Test
	void strongerWindsAloftDriftFarther() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation base = sim(d);
		Simulation flat = Variants.of(base, d.doc, null, null);
		Winds.setProfile(flat.getOptions(), List.of(new Winds.Level(0, 5, Math.PI / 2, 0.01)), true);
		Simulation sheared = Variants.of(base, d.doc, null, null);
		Winds.setProfile(sheared.getOptions(), List.of(new Winds.Level(0, 5, Math.PI / 2, 0.01),
				new Winds.Level(300, 15, Math.PI / 2, 0.01)), true);
		List<Variants.Run> r = Variants.runAll(List.of(flat, sheared));
		double d1 = Branch.of(r.get(0).sim().getSimulatedData().getBranch(0)).last(info.openrocket.core.simulation.FlightDataType.TYPE_POSITION_XY);
		double d2 = Branch.of(r.get(1).sim().getSimulatedData().getBranch(0)).last(info.openrocket.core.simulation.FlightDataType.TYPE_POSITION_XY);
		assertTrue(d2 > 1.5 * d1, d1 + " vs " + d2);
	}

	@Test
	void groundOverrideScalesAndRotatesTheProfile() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation s = sim(d);
		Winds.setProfile(s.getOptions(), List.of(new Winds.Level(0, 4, 0, 0.4), new Winds.Level(1000, 12, 0.5, 1.2)), true);
		Simulation copy = Variants.of(s, d.doc, null, null);
		Winds.setGround(copy.getOptions(), 8, 1.0);
		List<Winds.Level> l = Winds.levels(copy.getOptions());
		assertEquals(8, l.get(0).speed(), 1e-9);
		assertEquals(24, l.get(1).speed(), 1e-9, "shape kept");
		assertEquals(2.4, l.get(1).sd(), 1e-9, "turbulence scales with speed");
		assertEquals(1.0, l.get(0).direction(), 1e-9);
		assertEquals(1.5, l.get(1).direction(), 1e-9, "veer kept");
		assertTrue(Winds.isMultiLevel(copy.getOptions()));
		// The original is untouched (options are deep-copied)
		assertEquals(4, Winds.levels(s.getOptions()).get(0).speed(), 1e-9);
		assertEquals(12, Winds.levels(s.getOptions()).get(1).speed(), 1e-9);
		// Average model: setGround just sets the single wind
		Simulation avg = Variants.of(s, d.doc, null, null);
		Winds.useAverage(avg.getOptions());
		Winds.setGround(avg.getOptions(), 7, Double.NaN);
		assertEquals(7, Winds.speed(avg.getOptions()), 1e-12);
		assertTrue(!Winds.isMultiLevel(avg.getOptions()));
	}

	@Test
	void monteCarloKeepsTheBaseProfile() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation s = sim(d);
		Winds.setProfile(s.getOptions(), Winds.powerLaw(5, 0, 1.0 / 7, 2000, 0.1), true);
		String before = Winds.describe(s.getOptions()).toString();
		MonteCarlo.run(s, d.doc, new MonteCarlo.Settings(6, Double.NaN, 2, Double.NaN, 0.5, Double.NaN, 0.02, 0.05, Double.NaN, 1),
				Standards.defaults());
		assertEquals(before, Winds.describe(s.getOptions()).toString());
	}
}
