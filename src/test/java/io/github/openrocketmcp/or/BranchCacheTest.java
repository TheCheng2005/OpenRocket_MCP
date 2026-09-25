package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.standards.Standards;

/** The flight-data cache must not keep simulations alive (it once did, and long sessions ran out of memory). */
class BranchCacheTest {
	static WeakReference<FlightDataBranch> simulateAndDrop() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Variants.runAll(java.util.List.of(Variants.of(sim, d.doc, null, null)));
		Sims.run(sim);
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		assertTrue(Branch.of(b).col(FlightDataType.TYPE_ALTITUDE).length > 10, "cached and readable");
		return new WeakReference<>(b);
	}

	@Test
	void cachedFlightsCanBeCollected() throws Exception {
		WeakReference<FlightDataBranch> ref = simulateAndDrop();
		for (int i = 0; i < 50 && ref.get() != null; i++) {
			System.gc();
			byte[][] pressure = new byte[16][];
			for (int k = 0; k < pressure.length; k++) {
				pressure[k] = new byte[1 << 20];
			}
			Thread.sleep(20);
		}
		assertNull(ref.get(), "a dropped simulation's flight data is still reachable through the cache");
	}
}
