package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.masscalc.RigidBody;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;
import io.github.openrocketmcp.standards.Standards;

/** The caching engine flies exactly the same flights as OpenRocket's own, only faster. */
class FastSimTest {
	@Test
	void everyBundledDesignFliesIdentically() throws Exception {
		List<Designs.Design> designs = new ArrayList<>();
		Designs ds = new Designs();
		for (String name : Designs.EXAMPLES) {
			designs.add(ds.openExample(name));
		}
		designs.add(ds.open(Path.of("docs/examples/maple-10k-pdr.ork")));
		long slow = 0, fast = 0;
		int compared = 0;
		for (Designs.Design d : designs) {
			for (Simulation base : d.doc.getSimulations()) {
				Simulation a = Sims.prepare(d, base.getName(), null, Sims.Overrides.none(), Standards.defaults(), false);
				Simulation b = Sims.prepare(d, base.getName(), null, Sims.Overrides.none(), Standards.defaults(), false);
				Variants.seed(a.getOptions(), a.getOptions().getRandomSeed());
				Analysis.settle(a.getRocket().getFlightConfiguration(a.getFlightConfigurationId()));
				long t0 = System.nanoTime();
				try {
					a.simulate(new Watchdog()); // OpenRocket's engine: Sims.run is what installs the fast one
				} catch (Exception e) {
					continue; // e.g. a configuration without motors; Sims.run reports those
				}
				long t1 = System.nanoTime();
				Sims.run(b);
				long t2 = System.nanoTime();
				slow += t1 - t0;
				fast += t2 - t1;
				same(d.doc.getRocket().getName() + " / " + base.getName(), a.getSimulatedData(), b.getSimulatedData());
				compared++;
			}
		}
		assertTrue(compared >= 15, "compared " + compared + " simulations");
		System.out.printf("FastSim: %d simulations, OpenRocket engine %.2f s, caching engine %.2f s%n", compared,
				slow / 1e9, fast / 1e9);
	}

	private static void same(String what, FlightData a, FlightData b) {
		assertEquals(a.getBranchCount(), b.getBranchCount(), what + ": branches");
		for (int i = 0; i < a.getBranchCount(); i++) {
			FlightDataBranch x = a.getBranch(i), y = b.getBranch(i);
			assertEquals(x.getLength(), y.getLength(), what + ": points in branch " + i);
			for (FlightDataType t : x.getTypes()) {
				List<Double> u = x.get(t), v = y.get(t);
				for (int k = 0; k < u.size(); k++) {
					double p = u.get(k), q = v.get(k);
					if (!(Double.isNaN(p) && Double.isNaN(q))) {
						assertEquals(p, q, 0.0, what + ": " + t.getName() + " at point " + k + " of branch " + i);
					}
				}
			}
			assertEquals(x.getEvents().size(), y.getEvents().size(), what + ": events");
		}
		assertEquals(warnings(a), warnings(b), what + ": warnings");
	}

	private static List<String> warnings(FlightData d) {
		List<String> out = new ArrayList<>();
		d.getWarningSet().forEach(w -> out.add(w.toString()));
		out.sort(null);
		return out;
	}

	@Test
	void listenersThatTouchTheMassTurnTheCacheOff() {
		assertFalse(FastSim.hooksMass(new Watchdog()));
		assertTrue(FastSim.hooksMass(new AbstractSimulationListener() {
			@Override
			public RigidBody postMassCalculation(SimulationStatus status, RigidBody body) {
				return body;
			}
		}));
	}
}
