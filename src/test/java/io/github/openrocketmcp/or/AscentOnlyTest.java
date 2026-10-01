package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import io.github.openrocketmcp.standards.Standards;

/** Stopping after the ascent leaves every ascent number unchanged and skips the descent. */
class AscentOnlyTest {
	@Test
	void ascentNumbersAreUnchanged() throws Exception {
		for (String example : new String[] { "Dual parachute", "Two stage high power", "A simple model rocket" }) {
			Designs.Design d = new Designs().openExample(example);
			Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
			Simulation full = Variants.of(base, d.doc, null, null);
			Simulation cut = Variants.ascentOnly(Variants.of(base, d.doc, null, null));
			long t0 = System.nanoTime();
			List<Variants.Run> runs = Variants.runAll(List.of(full));
			long t1 = System.nanoTime();
			runs = List.of(runs.get(0), Variants.runAll(List.of(cut)).get(0));
			long t2 = System.nanoTime();
			FlightData a = runs.get(0).sim().getSimulatedData(), b = runs.get(1).sim().getSimulatedData();
			assertEquals(a.getMaxAltitude(), b.getMaxAltitude(), 1e-9, example);
			assertEquals(a.getLaunchRodVelocity(), b.getLaunchRodVelocity(), 1e-9, example);
			assertEquals(a.getMaxMachNumber(), b.getMaxMachNumber(), 1e-9, example);
			assertEquals(a.getOptimumDelay(), b.getOptimumDelay(), 1e-9, example);
			Sims.Window wa = Sims.ascentStability(a.getBranch(0)), wb = Sims.ascentStability(b.getBranch(0));
			assertEquals(wa.min(), wb.min(), 1e-9, example);
			assertEquals(wa.max(), wb.max(), 1e-9, example);
			assertEquals(Sims.deployments(runs.get(0).sim()).get(0).airspeed(), Sims.deployments(runs.get(1).sim()).get(0).airspeed(),
					1e-9, example);
			int full0 = a.getBranch(0).getLength(), cut0 = b.getBranch(0).getLength();
			System.out.printf("%s: %d -> %d steps, %.0f -> %.0f ms%n", example, full0, cut0, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
			assertTrue(cut0 < full0, "the descent is skipped");
		}
	}
}
