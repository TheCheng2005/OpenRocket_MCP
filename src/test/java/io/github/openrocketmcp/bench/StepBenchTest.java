package io.github.openrocketmcp.bench;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.standards.Standards;

class StepBenchTest {
	@Test
	void timeStepAccuracy() throws Exception {
		if (!Boolean.getBoolean("bench")) {
			return;
		}
		StringBuilder out = new StringBuilder();
		for (String ex : new String[] { "Dual parachute", "Two stage high power" }) {
			Designs.Design d = new Designs().openExample(ex);
			Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
			out.append(ex).append(" default dt=").append(base.getOptions().getTimeStep())
					.append(" maxAngle=").append(base.getOptions().getMaximumStepAngle()).append('\n');
			for (double dt : new double[] { base.getOptions().getTimeStep(), 0.02, 0.05, 0.1 }) {
				Simulation s = base.copy();
				s.getOptions().setTimeStep(dt);
				s.getOptions().setWindSpeedAverage(4);
				Sims.run(s); // warm
				long t0 = System.nanoTime();
				for (int i = 0; i < 5; i++) {
					s = s.copy();
					Sims.run(s);
				}
				double ms = (System.nanoTime() - t0) / 5e6;
				FlightData fd = s.getSimulatedData();
				FlightDataBranch b = fd.getBranch(0);
				out.append(String.format("  dt=%.3f  %.0f ms  apogee=%.1f  points=%d  landing=%.1f  railExit=%.2f%n", dt, ms,
						fd.getMaxAltitude(), b.getLength(), b.getLast(FlightDataType.TYPE_POSITION_XY), fd.getLaunchRodVelocity()));
			}
		}
		java.nio.file.Files.writeString(java.nio.file.Path.of("build/step-bench.txt"), out.toString());
	}
}
