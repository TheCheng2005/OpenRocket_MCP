package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.FlightConfiguration;

class AeroTest {
	@Test
	void dragComponentsAddUpAndCpMatchesAnalysis() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		List<Aero.Point> pts = Aero.sweep(fc, new double[] { 0.3, 0.8, 1.0, 1.2, 2.0 });
		for (Aero.Point p : pts) {
			assertEquals(p.cd(), p.friction() + p.pressure() + p.base(), 1e-6 + 0.02 * p.cd(), "M" + p.mach());
			assertTrue(p.cd() > 0.1 && p.cd() < 3, "plausible CD " + p.cd());
		}
		assertEquals(Analysis.cp(fc, 0.3), pts.get(0).cpX(), 1e-9);
		assertEquals(Analysis.stability(fc, 0.3).marginCalibers(), pts.get(0).marginLaunch(), 1e-9);
		assertTrue(pts.get(2).cd() > pts.get(0).cd() * 1.1, "transonic drag rise");
		assertTrue(pts.get(0).marginBurnout() > pts.get(0).marginLaunch(), "CG moves forward as propellant burns");
	}

	@Test
	void breakdownSharesSumTo100() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		List<Map<String, Object>> rows = Aero.breakdown(d.doc.getRocket().getSelectedConfiguration(), 0.5);
		double sum = 0, prev = Double.MAX_VALUE;
		for (Map<String, Object> r : rows) {
			double share = Double.parseDouble(r.get("share").toString().replace("%", ""));
			sum += share;
			double cd = Double.parseDouble(r.get("cd").toString());
			assertTrue(cd <= prev + 1e-9, "sorted by drag");
			prev = cd;
		}
		assertEquals(100, sum, 0.1);
		assertTrue(rows.size() >= 5);
	}

	@Test
	void staticStabilityIsSettledOnTheFirstCall() throws Exception {
		// OpenRocket's first mass calculations after loading disagree by ~1 mm of CG; Designs settles them on open.
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		double first = info.openrocket.core.masscalc.MassCalculator.calculateLaunch(fc).getCM().x;
		for (int i = 0; i < 3; i++) {
			assertEquals(first, info.openrocket.core.masscalc.MassCalculator.calculateLaunch(fc).getCM().x, 1e-12);
		}
	}
}
