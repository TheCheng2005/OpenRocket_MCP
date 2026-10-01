package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.standards.Standards;

/** Simulated flight-computer data: the physics of what each sensor reads, noise and range, and the range check. */
class SensorSimTest {
	static Simulation flown(String example) throws Exception {
		Designs.Design d = new Designs().openExample(example);
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		return sim;
	}

	/** The accelerometer must agree with the trajectory: dvz/dt = a_axial cos(tilt) - g on a near-vertical climb. */
	@Test
	void theAccelerometerAgreesWithTheTrajectory() throws Exception {
		Simulation sim = flown("Dual parachute");
		double cos = Math.cos(sim.getOptions().getLaunchRodAngle());
		SensorSim.Trace tr = SensorSim.trace(sim, null, 100, 2, 2);
		int checked = 0;
		for (int k = 5; k < tr.size() - 5; k++) {
			String ph = tr.phase()[k];
			// Away from phase changes (thrust tail-off happens inside one sample there).
			if ((ph.equals("boost") || ph.equals("coast")) && ph.equals(tr.phase()[k - 5]) && ph.equals(tr.phase()[k + 5])
					&& tr.speed()[k] > 30) {
				double dvz = (tr.vz()[k + 1] - tr.vz()[k - 1]) / (tr.t()[k + 1] - tr.t()[k - 1]);
				double sensed = tr.axial()[k] * SensorSim.G * cos - SensorSim.G;
				assertEquals(dvz, sensed, 1.0 + 0.005 * Math.abs(dvz), "t=" + tr.t()[k] + " " + ph);
				checked++;
			}
		}
		assertTrue(checked > 200, "checked " + checked + " samples");
	}

	@Test
	void restOnThePadAndHangingUnderTheCanopyReadOneG() throws Exception {
		Simulation sim = flown("Dual parachute");
		sim.getOptions().setLaunchRodAngle(Math.toRadians(6));
		Sims.run(sim);
		SensorSim.Trace tr = SensorSim.trace(sim, null, 100, 2, 2);
		assertEquals("pad", tr.phase()[0]);
		assertEquals(Math.cos(Math.toRadians(6)), tr.axial()[0], 1e-6, "+1 g along the tilted rail");
		assertEquals(Math.sin(Math.toRadians(6)), tr.lateral()[0], 1e-6);
		// Steady descent under the main, well before landing: the canopy holds the weight.
		int k = tr.size() - (int) (2 * 100) - 300;
		assertEquals("descent", tr.phase()[k]);
		assertEquals(-1, tr.axial()[k], 0.05);
		assertEquals("landed", tr.phase()[tr.size() - 1]);
		assertEquals(2.0, tr.padTime(), 1e-9);
	}

	@Test
	void sensorsAddNoiseRoundAndClip() {
		SensorSim.Spec acc = new SensorSim.Spec("acc", "16 g part", "accelerometer", 16, 0.01, 0.0005, Double.NaN, Double.NaN);
		Random rnd = new Random(3);
		assertEquals(16, SensorSim.measure(40, acc, rnd), 1e-12, "clips at full scale");
		assertEquals(-16, SensorSim.measure(-40, acc, rnd), 1e-12);
		double v = SensorSim.measure(1, acc, rnd);
		assertEquals(0, Math.abs(v / 0.0005 - Math.round(v / 0.0005)), 1e-6, "a whole number of counts");
		double sum = 0, sq = 0;
		int n = 20000;
		for (int i = 0; i < n; i++) {
			double e = SensorSim.measure(1, acc, rnd) - 1;
			sum += e;
			sq += e * e;
		}
		assertEquals(0.01, Math.sqrt(sq / n - (sum / n) * (sum / n)), 0.001, "1-sigma noise");
		SensorSim.Spec baro = new SensorSim.Spec("baro", "b", "barometer", Double.NaN, 0, 0, 30000, 125000);
		assertEquals(30000, SensorSim.measure(20000, baro, rnd), 1e-9, "below the barometer's range");
	}

	@Test
	void theCsvHasEverySensorTheTruthAndTheEvents(@TempDir Path tmp) throws Exception {
		SensorSim.Trace tr = SensorSim.trace(flown("Dual parachute"), null, 50, 1, 1);
		List<SensorSim.Spec> specs = SensorSim.specs(Standards.defaults().data().getAsJsonObject("electronics")
				.getAsJsonArray("sensors"));
		Path csv = tmp.resolve("f.csv");
		int[] size = SensorSim.write(tr, specs, csv, 10, 1, true);
		List<String> lines = Files.readAllLines(csv);
		assertEquals(size[0] + 1, lines.size());
		String header = lines.get(0);
		for (String c : new String[] { "acc_x_g", "acc_hi_z_g", "gyro_y_dps", "baro_pressure_pa", "gps_lat_deg", "truth_phase" }) {
			assertTrue(header.contains(c), c);
		}
		assertEquals(size[1], header.split(",").length);
		// GPS every 5th row at 50 Hz / 10 Hz.
		int gpsCol = List.of(header.split(",")).indexOf("gps_fix");
		assertEquals("1", lines.get(1).split(",", -1)[gpsCol]);
		assertEquals("", lines.get(2).split(",", -1)[gpsCol]);
		List<String> events = Files.readAllLines(SensorSim.eventsPath(csv));
		assertTrue(events.stream().anyMatch(l -> l.contains(",apogee,")), events.toString());
		// The same seed gives the same file.
		Path again = tmp.resolve("g.csv");
		SensorSim.write(tr, specs, again, 10, 1, true);
		assertEquals(lines, Files.readAllLines(again));
	}

	@Test
	void rangesAreCheckedAgainstTheFlight() throws Exception {
		SensorSim.Trace tr = SensorSim.trace(flown("Dual parachute"), null, 200, 0, 0);
		JsonArray specs = JsonParser.parseString("[{\"id\":\"a\",\"type\":\"accelerometer\",\"range\":2},"
				+ "{\"id\":\"b\",\"type\":\"barometer\",\"min\":95000,\"max\":125000}]").getAsJsonArray();
		List<Map<String, Object>> rows = SensorSim.check(tr, SensorSim.specs(specs), 0.7);
		Map<String, Object> boost = rows.get(0);
		assertEquals("FAIL", boost.get("status"), boost.toString());
		assertTrue(boost.get("note").toString().contains("high-g"));
		Map<String, Object> baro = rows.stream().filter(r -> r.get("quantity").toString().startsWith("pressure")).findFirst().orElseThrow();
		assertEquals("FAIL", baro.get("status"), "apogee is above 95 kPa's altitude: " + baro);
		assertTrue(rows.stream().noneMatch(r -> r.get("quantity").toString().contains("Mach")), "subsonic: no lockout window");
	}

	@Test
	void transonicFlightsGetABarometerWindow() throws Exception {
		Designs.Design d = new Designs().open(Path.of("docs/examples/maple-10k.ork"));
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		SensorSim.Trace tr = SensorSim.trace(sim, null, 200, 0, 0);
		double[] w = SensorSim.baroWindow(tr, 0.7);
		assertTrue(w[0] > 0 && w[1] > w[0] && w[1] < 15, "window " + w[0] + " - " + w[1]);
	}
}
