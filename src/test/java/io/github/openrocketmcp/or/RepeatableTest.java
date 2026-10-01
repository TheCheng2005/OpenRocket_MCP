package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.standards.Standards;

/** The same file gives the same flight every time it is opened (OpenRocket does not save the random seed). */
class RepeatableTest {
	@Test
	void reopeningADesignGivesTheSameFlight(@TempDir Path tmp) throws Exception {
		Designs ds = new Designs();
		Path f = ds.save(ds.openExample("Two stage high power"), tmp.resolve("two.ork"));
		double[] first = null;
		for (int i = 0; i < 2; i++) {
			Designs.Design d = new Designs().open(f);
			assertTrue(d.doc.isSaved(), "opening a file does not mark it changed");
			Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
			Sims.run(sim);
			double[] got = { sim.getSimulatedData().getMaxAltitude(),
					Branch.of(sim.getSimulatedData().getBranch(0)).last(FlightDataType.TYPE_POSITION_XY) };
			if (first == null) {
				first = got;
			} else {
				assertEquals(first[0], got[0], 1e-9, "apogee");
				assertEquals(first[1], got[1], 1e-9, "landing distance (turbulence)");
			}
		}
	}
}
