package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import io.github.openrocketmcp.standards.Standards;

/** optimize_fins: the shape space only holds buildable fins, and the search meets its constraints. */
class FinDesignTest {
	static final FinDesign.Limits LIM = new FinDesign.Limits(0.08, 0.30, 0.05, 0.20, 0.2, 1.0, 0.0127, 1.0, Math.toRadians(60));

	@Test
	void everyPointOfTheSearchSpaceIsBuildable() {
		Random rnd = new Random(7);
		for (int i = 0; i < 20000; i++) {
			double[] x = { 0.08 + 0.22 * rnd.nextDouble(), 0.2 + 0.8 * rnd.nextDouble(), 0.05 + 0.15 * rnd.nextDouble(),
					rnd.nextDouble() };
			FinDesign.Planform p = FinDesign.shape(x, LIM, 0.003);
			assertTrue(p.tip() >= 0.0127 - 1e-12, "tip chord floor: " + p);
			assertTrue(p.tip() <= p.root() + 1e-12, "no inverse taper: " + p);
			assertTrue(p.aftOverhang() <= 1e-12, "tip trailing edge not behind the root: " + p);
			assertTrue(p.sweepAngle() <= Math.toRadians(60) + 1e-9, "sweep cap: " + p);
			assertTrue(p.sweep() >= 0 && p.area() > 0);
		}
	}

	@Test
	void overhangOnlyWhenAllowed() {
		FinDesign.Limits open = new FinDesign.Limits(0.08, 0.30, 0.05, 0.20, 0.2, 1.0, 0.0127, 1.4, Math.toRadians(80));
		FinDesign.Planform p = FinDesign.shape(new double[] { 0.2, 0.3, 0.15, 1.3 }, open, 0.003);
		assertTrue(p.aftOverhang() > 0.01, p.toString());
	}

	@Test
	void theCurrentShapeMapsBackToItself() {
		FinDesign.Planform p = new FinDesign.Planform(0.2, 0.07, 0.12, 0.09, 0.003);
		FinDesign.Planform back = FinDesign.shape(FinDesign.variables(p, LIM), LIM, 0.003);
		assertEquals(p.root(), back.root(), 1e-12);
		assertEquals(p.tip(), back.tip(), 1e-12);
		assertEquals(p.span(), back.span(), 1e-12);
		assertEquals(p.sweep(), back.sweep(), 1e-12);
	}

	@Test
	void findsStableFinsFromUndersizedOnesWithoutTouchingTheDesign() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		TrapezoidFinSet fin = StudiesTest.first(d, TrapezoidFinSet.class);
		fin.setHeight(fin.getHeight() * 0.4); // too small to be stable
		FinDesign.Planform before = FinDesign.of(fin);
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		double diameter = 2 * fin.getBodyRadius();
		FinDesign.Limits lim = FinDesign.defaults(fin, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, Double.NaN);
		assertTrue(lim.spanMax() > 1.9 * diameter && lim.rootMax() <= fin.getParent().getLength() + 1e-12);
		Optimizer.Constraints c = new Optimizer.Constraints(1.5, 5, Double.NaN, Double.NaN, Double.NaN);
		Optimizer.Point cur = Optimizer.evaluateOne(base, d.doc, (r, x) -> {
		}, new double[0], Double.NaN, null);
		assertTrue(cur.minStability() < 1.5, "the starting fins are unstable: " + cur.minStability());

		List<FinDesign.Run> runs = FinDesign.optimize(base, d.doc, fin, lim, List.of(fin.getThickness()),
				Optimizer.Objective.MAX_APOGEE, Double.NaN, c, 24, Double.NaN);
		Optimizer.Result r = runs.get(0).result();
		assertTrue(r.feasible(), Optimizer.violations(r.best(), c).toString());
		assertTrue(r.best().minStability() >= 1.5 && r.best().maxStability() <= 5);
		assertEquals(before, FinDesign.of(fin), "optimize does not edit the design");

		FinDesign.Planform best = FinDesign.shape(r.best().x(), lim, fin.getThickness());
		FinDesign.applyTo(fin, best);
		assertEquals(best.span(), fin.getHeight(), 1e-12);
		assertEquals(best.sweep(), fin.getSweep(), 1e-12);
		String svg = FinDesign.svg("t", before, best, true);
		assertEquals(2, svg.split("<polygon").length - 1);
	}

	@Test
	void finCandidatesFlyOnOpenRocketsDragEvenWithAnImportedTable() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		TrapezoidFinSet fin = StudiesTest.first(d, TrapezoidFinSet.class);
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Optimizer.Applier none = (r, x) -> {
		};
		double own = Optimizer.evaluateOne(base, d.doc, none, new double[0], Double.NaN, null).apogee();
		AeroTable.set(d.doc.getRocket(), AeroTable.parse("Mach,CD\n0.01,3.0\n3.0,3.0\n", "draggy", 1.0, true));
		try {
			double table = Optimizer.evaluateOne(base, d.doc, none, new double[0], Double.NaN, null).apogee();
			assertTrue(table < own * 0.7, "the table applies to ordinary variants: " + table + " vs " + own);
			FinDesign.Limits lim = FinDesign.defaults(fin, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, Double.NaN);
			Optimizer.Applier ap = FinDesign.applier(fin.getID().toString(), lim, fin.getThickness());
			assertTrue(ap.openRocketDrag());
			double[] x = FinDesign.variables(FinDesign.of(fin), lim);
			double fins = Optimizer.evaluateOne(base, d.doc, ap, x, Double.NaN, null).apogee();
			assertEquals(own, fins, own * 0.01, "fin candidates ignore a table measured on the old fins");
		} finally {
			AeroTable.clear(d.doc.getRocket());
		}
	}
}
