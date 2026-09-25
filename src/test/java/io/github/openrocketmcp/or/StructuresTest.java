package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.calc.Flutter;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;

class StructuresTest {
	static Simulation simulated(Designs.Design d) {
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.run(sim);
		return sim;
	}

	static <T> T first(Designs.Design d, Class<T> type) {
		for (RocketComponent c : d.doc.getRocket()) {
			if (type.isInstance(c)) {
				return type.cast(c);
			}
		}
		throw new AssertionError("no " + type.getSimpleName());
	}

	@Test
	void trapezoidGeometryMatchesComponent() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FinSet f = first(d, FinSet.class);
		Flutter.Fin g = Structures.geometry(f, Double.NaN);
		if (f instanceof info.openrocket.core.rocketcomponent.TrapezoidFinSet t) {
			assertEquals(t.getRootChord(), g.rootChord(), 1e-9);
			assertEquals(t.getTipChord(), g.tipChord(), 1e-9);
			assertEquals(t.getHeight(), g.span(), 1e-9);
		}
		assertEquals(f.getThickness(), g.thickness(), 1e-12);
		assertEquals(0.01, Structures.geometry(f, 0.01).thickness(), 1e-12);
	}

	@Test
	void flutterAlongFlightIsConsistent() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		Simulation sim = simulated(d);
		Standards std = Standards.defaults();
		List<Structures.FlutterResult> base = Structures.flutter(sim, std, Map.of("*", 2.9e9), Double.NaN);
		assertEquals(2, base.size(), "booster and sustainer fin sets");
		for (Structures.FlutterResult r : base) {
			assertTrue(r.minMargin() > 0 && r.airspeed() > 0, r.toString());
			assertEquals(r.flutterSpeed() / r.airspeed(), r.minMargin(), 1e-9);
			Map<String, Object> m = Structures.render(r, std);
			assertTrue(m.containsKey("status") && m.containsKey("worstPoint"), m.toString());
		}
		// Twice the thickness: every margin grows by 2^1.5 at the same worst point or better
		List<Structures.FlutterResult> thick = Structures.flutter(sim, std, Map.of("*", 2.9e9), 2 * base.get(0).geom().thickness());
		assertTrue(thick.get(0).minMargin() > base.get(0).minMargin() * 2.5);
		// Booster fins are only checked until the booster separates
		double sep = Double.NaN;
		for (var e : sim.getSimulatedData().getBranch(0).getEvents()) {
			if (e.getType() == info.openrocket.core.simulation.FlightEvent.Type.STAGE_SEPARATION) {
				sep = e.getTime();
			}
		}
		assertFalse(Double.isNaN(sep));
		for (Structures.FlutterResult r : base) {
			if (r.fin().getStage().getName().toLowerCase().contains("booster")) {
				assertTrue(r.time() <= sep + 1e-9, "booster worst point " + r.time() + " after separation " + sep);
			}
		}
	}

	@Test
	void unknownMaterialNeedsModulus() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = simulated(d);
		Standards none = Standards.defaults().patched(JsonParser.parseString("{\"structures\":{\"shearModulus\":null}}").getAsJsonObject());
		ToolException e = assertThrows(ToolException.class, () -> Structures.flutter(sim, none, null, Double.NaN));
		assertTrue(e.getMessage().contains("shearModulus"));
		// Requirements degrade to an INFO item instead of failing the whole check
		Requirements.Report r = Requirements.check(sim, null, none);
		assertTrue(r.items.stream().anyMatch(i -> "INFO".equals(i.get("status")) && String.valueOf(i.get("item")).equals("Fin flutter")));
	}

	@Test
	void analyticBallastReachesStaticTarget() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		for (RocketComponent c : d.doc.getRocket()) {
			c.setMassOverridden(false); // the example's sustainer overrides its weighed mass; test the plain formula
			c.setCGOverridden(false);
		}
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		NoseCone nose = first(d, NoseCone.class);
		double local = 0.6 * nose.getLength();
		double x = Structures.absoluteX(nose, local);
		double now = Analysis.stability(fc, 0.3).marginCalibers();
		double target = now + 0.75;
		double m = Structures.analyticBallast(fc, 0.3, target, x);
		assertTrue(m > 0);
		Structures.addBallast(nose, local, m);
		assertEquals(target, Analysis.stability(d.doc.getRocket().getSelectedConfiguration(), 0.3).marginCalibers(), 0.01,
				"ballast centered where the formula assumed");
		// Already above target: negative ballast (mass that could come out)
		assertTrue(Structures.analyticBallast(fc, 0.3, now - 0.5, x) < 0);
		// Ballast aft of the target CG can never reach it
		assertTrue(Double.isNaN(Structures.analyticBallast(fc, 0.3, target, fc.getLength())));
	}

	@Test
	void ballastRaisesAWeighedMassOverride() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		RocketComponent stage = null;
		for (RocketComponent c : d.doc.getRocket()) {
			if (c.isMassOverridden() && c.isSubcomponentsOverriddenMass()) {
				stage = c;
			}
		}
		org.junit.jupiter.api.Assertions.assertNotNull(stage, "example has a weighed-mass override");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		double before = info.openrocket.core.masscalc.MassCalculator.calculateLaunch(fc).getMass();
		double cgBefore = info.openrocket.core.masscalc.MassCalculator.calculateLaunch(fc).getCM().x;
		NoseCone nose = first(d, NoseCone.class);
		Structures.addBallast(nose, 0.1, 0.2);
		fc = d.doc.getRocket().getSelectedConfiguration();
		assertEquals(before + 0.2, info.openrocket.core.masscalc.MassCalculator.calculateLaunch(fc).getMass(), 1e-9);
		assertTrue(info.openrocket.core.masscalc.MassCalculator.calculateLaunch(fc).getCM().x < cgBefore, "CG moves forward");
	}

	@Test
	void simulatedBallastSolveHitsTarget() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(base);
		double now = Structures.minStab(base);
		NoseCone nose = first(d, NoseCone.class);
		int before = d.doc.getRocket().getChildCount();
		Structures.Ballast b = Structures.ballast(base, d.doc, nose, 0.6 * nose.getLength(), now + 0.5);
		assertTrue(b.mass() > 0);
		assertEquals(now + 0.5, b.minStability(), 0.05, "simulated minimum stability at the target");
		assertTrue(b.apogee() != b.baseApogee(), "ballast changes the flight");
		assertTrue(b.note() != null && b.note().contains("overrides the mass"), "the example's weighed-mass override is explained: " + b.note());
		assertTrue(b.simulations() <= 5);
		assertEquals(before, d.doc.getRocket().getChildCount());
		for (RocketComponent c : d.doc.getRocket()) {
			assertFalse(c.getName().startsWith("Ballast"), "the open design is not modified");
		}
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({ "Two stage high power,1.9", "Two stage high power,2.2", "Two stage high power,2.6",
			"Dual parachute,4.5", "Dual parachute,5.2" })
	void ballastSolveConvergesForManyTargets(String example, double target) throws Exception {
		Designs.Design d = new Designs().openExample(example);
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		NoseCone nose = first(d, NoseCone.class);
		Structures.Ballast b = Structures.ballast(base, d.doc, nose, 0.6 * nose.getLength(), target);
		Structures.Ballast again = Structures.ballast(Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false),
				d.doc, nose, 0.6 * nose.getLength(), target);
		assertEquals(b.baseMinStability(), again.baseMinStability(), 1e-12, "repeatable across calls");
		assertTrue(b.minStability() >= target - 0.03, b.toString());
		// The simulated minimum can jump across the target (platform arithmetic moves the jump); an overshoot must then
		// be explained to the user.
		assertTrue(b.minStability() <= target + 0.15 || (b.note() != null && b.note().contains("jumps")),
				"overshoot without explanation: " + b);
	}

	@Test
	void ballastWhenOnlyTheSimulatedMinimumIsShort() throws Exception {
		// Two-stage example: static margin at launch already meets 2 cal, the simulated minimum (1.7 cal) does not.
		Designs.Design d = new Designs().openExample("Two stage high power");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		NoseCone nose = first(d, NoseCone.class);
		Structures.Ballast b = Structures.ballast(base, d.doc, nose, 0.6 * nose.getLength(), 2.0);
		assertTrue(b.baseMinStability() < 2.0, "precondition: " + b.baseMinStability());
		assertTrue(b.mass() > 0, "needs ballast even though the static estimate says none: " + b);
		// The simulated minimum can jump across the target (it is a minimum over time), so the solver returns the
		// lightest mass found that meets it.
		assertTrue(b.minStability() >= 2.0 - 0.03 && b.minStability() <= 2.2, b.toString());
	}
}
