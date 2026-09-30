package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;

/** Rail button placement: rail used, tip-off, slop, loads, and moving the buttons in the design. */
class RailButtonsTest {
	static final RailButtons.Options OPTS = new RailButtons.Options(0, 0.001, 30 / 3.6, 0, 30.48);

	static Simulation sim(Designs.Design d) {
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		return sim;
	}

	@Test
	void theLowerTheAftButtonTheFasterTheRocketLeavesTheRail() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		RailButtons.Model m = new RailButtons.Model(sim(d), OPTS);
		double end = m.stations.get(m.stations.size() - 1).x1();
		RailButtons.Layout low = m.evaluate(end - 0.5, end - 0.02), high = m.evaluate(end - 0.5, end - 0.2);
		assertTrue(low.railExitVelocity() > high.railExitVelocity());
		assertEquals(0.18, low.guidedTravel() - high.guidedTravel(), 1e-9, "every cm above the aft end is rail lost");
		assertTrue(low.railExitVelocity() <= m.railExitOr + 1e-6, "OpenRocket counts the whole rail");
	}

	@Test
	void spacingTradesTipOffAgainstSlop() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		RailButtons.Model m = new RailButtons.Model(sim(d), OPTS);
		double aft = m.stations.get(m.stations.size() - 1).x1() - 0.02;
		RailButtons.Layout near = m.evaluate(aft - 0.15, aft), far = m.evaluate(aft - 0.6, aft);
		assertTrue(far.singleButtonTime() > near.singleButtonTime());
		assertTrue(far.tipOffRate() > near.tipOffRate());
		assertTrue(far.tipOffAngle() > near.tipOffAngle());
		assertTrue(far.slopAngle() < near.slopAngle());
		assertEquals(0.001 / 0.6, far.slopAngle(), 1e-9);
		// With both buttons on, the couple carrying the side forces shrinks with the spacing.
		assertTrue(Math.abs(near.fwdLoad()) > Math.abs(far.fwdLoad()));
		// Without wind the buttons carry just the weight across the tilted rail, whatever the spacing.
		RailButtons.Model calm = new RailButtons.Model(sim(d), new RailButtons.Options(0, 0.001, 0, 0, 30.48));
		double across = calm.mass * 9.80665 * Math.sin(calm.angle);
		for (double s : new double[] { 0.15, 0.6 }) {
			RailButtons.Layout l = calm.evaluate(aft - s, aft);
			assertEquals(across, l.fwdLoad() + l.aftLoad(), 1e-9);
			assertEquals(across * (aft - calm.xcg) / s, l.fwdLoad(), 1e-9);
		}
	}

	@Test
	void theRecommendationBeatsTheExampleLayoutAndCanBeApplied() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		Simulation sim = sim(d);
		@SuppressWarnings("unchecked")
		Map<String, Object> out = RailButtons.analyse(sim, OPTS, true, null);
		assertTrue(out.containsKey("current") && out.containsKey("recommended"), out.toString());
		RailButtons.Model m = new RailButtons.Model(sim, OPTS);
		List<double[]> placed = RailButtons.existing(m.fc);
		assertEquals(2, placed.size());
		RailButtons.Layout rec = RailButtons.optimise(m, placed.get(1)[0]);
		assertEquals(rec.xFwd(), placed.get(0)[0], 1e-4, "forward button where recommended");
		assertEquals(rec.xAft(), placed.get(1)[0], 1e-4, "aft button where recommended");
		// Applying again changes nothing.
		RailButtons.analyse(sim, OPTS, true, null);
		List<double[]> again = RailButtons.existing(new RailButtons.Model(sim, OPTS).fc);
		assertEquals(placed.get(0)[0], again.get(0)[0], 1e-6);
		assertEquals(placed.get(1)[0], again.get(1)[0], 1e-6);
	}

	@Test
	void aDesignWithoutButtonsGetsADelrinPair() throws Exception {
		Designs.Design d = new Designs().openExample("A simple model rocket");
		Simulation sim = sim(d);
		RailButtons.analyse(sim, OPTS, true, null);
		int buttons = 0;
		for (RocketComponent c : d.doc.getRocket()) {
			if (c instanceof RailButton rb) {
				buttons += rb.getInstanceCount();
				assertTrue(rb.getMaterial().getName().toLowerCase().contains("delrin"), rb.getMaterial().getName());
			}
		}
		assertEquals(2, buttons);
	}

	@Test
	void aProposedLayoutMustSitOnTheAirframe() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = sim(d);
		assertThrows(ToolException.class, () -> RailButtons.analyse(sim, OPTS, false, new double[] { 0.01, 1.0 }),
				"the nose cone is no place for a button");
		Map<String, Object> out = RailButtons.analyse(sim, OPTS, false, new double[] { 0.9, 1.4 });
		assertTrue(out.containsKey("proposed"));
	}
}
