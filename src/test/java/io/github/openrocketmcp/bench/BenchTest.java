package io.github.openrocketmcp.bench;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.standards.Standards;

class BenchTest {
	@Test
	void bench() throws Exception {
		if (!Boolean.getBoolean("bench")) {
			return;
		}
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("Two stage high power");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.run(sim);
		for (int i = 0; i < 5; i++) {
			Sims.summarize(sim);
		}
		long t0 = System.nanoTime();
		for (int i = 0; i < 20; i++) {
			Sims.summarize(sim);
		}
		long t1 = System.nanoTime();
		for (int i = 0; i < 10; i++) {
			Sims.run(sim.copy());
		}
		long t2 = System.nanoTime();
		java.nio.file.Files.writeString(java.nio.file.Path.of("build/bench.txt"),
				"summarize ms: " + (t1 - t0) / 20e6 + "\nsimulate ms: " + (t2 - t1) / 10e6 + "\n");
	}
}
