package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.report.SvgTest;
import io.github.openrocketmcp.standards.Standards;

class FlightLogTest {
	/** An altimeter-style CSV (feet, pad at 300 ft MSL, 20 Hz, 2 s of pad data) from a simulation. */
	static String log(Simulation sim) {
		double[][] tr = FlightLog.simTrack(sim);
		StringBuilder sb = new StringBuilder("Time (s),Altitude (ft),Velocity (ft/s)\n");
		for (double t = -2; t < 0; t += 0.05) {
			sb.append(String.format(Locale.ROOT, "%.2f,%.1f,0%n", t + 2, 300.0));
		}
		for (int i = 0; i < tr[0].length; i++) {
			sb.append(String.format(Locale.ROOT, "%.3f,%.2f,0%n", tr[0][i] + 2, 300 + tr[1][i] / 0.3048));
		}
		return sb.toString();
	}

	@Test
	void parsesUnitsAndPadOffset() {
		FlightLog.Log l = FlightLog.parse("Time (ms),Altitude (m)\n0,100\n100,100\n200,100\n300,100\n400,100\n500,101\n600,105\n"
				+ "700,110\n800,120\n900,130\n1000,140\n", null);
		assertEquals(1.0, l.t()[l.t().length - 1], 1e-12, "milliseconds from the header");
		assertEquals(40, l.alt()[l.alt().length - 1], 1e-9, "metres, pad altitude removed");
		FlightLog.Log noHeader = FlightLog.parse("0,0\n1,0\n2,0\n3,10\n4,30\n5,60\n6,90\n7,100\n8,95\n9,80\n10,70\n", null);
		assertTrue(noHeader.note().contains("feet"));
		assertEquals(100 * 0.3048, noHeader.alt()[7], 1e-9);
		assertThrows(ToolException.class, () -> FlightLog.parse("t,alt\n0,0\n1,1", null));
	}

	@Test
	@SuppressWarnings("unchecked")
	void closedLoopRecoversTheDragFactor(@TempDir Path tmp) throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		// "Measured" flight = the same vehicle with 30% more drag.
		Simulation truth = Variants.listen(Variants.of(base, d.doc, null, null), new MonteCarlo.Scaling(1.3, 1));
		Variants.runAll(List.of(truth));
		FlightLog.Log log = FlightLog.parse(log(truth), null);
		Map<String, Object> out = FlightLog.compare(base, d.doc, log, tmp.resolve("overlay.svg").toString(), "test");
		String fit = ((Map<?, ?>) out.get("calibration")).get("dragFactor").toString();
		double f = Double.parseDouble(fit.split(" ")[0]);
		assertEquals(1.3, f, 0.05, fit);
		String svg = Files.readString(tmp.resolve("overlay.svg"));
		assertTrue(svg.contains("series2") && svg.contains("measured"));
		SvgTest.wellFormed(svg);
		// Same vehicle: apogee difference ~0 and factor ~1
		Simulation same = Variants.of(base, d.doc, null, null);
		Variants.runAll(List.of(same));
		Map<String, Object> o2 = FlightLog.compare(base, d.doc, FlightLog.parse(log(same), null), null, "t");
		double f2 = Double.parseDouble(((Map<?, ?>) o2.get("calibration")).get("dragFactor").toString().split(" ")[0]);
		assertEquals(1.0, f2, 0.03);
		List<Map<String, Object>> rows = (List<Map<String, Object>>) o2.get("comparison");
		String diff = rows.get(0).get("difference").toString();
		assertTrue(diff.startsWith("measured ") && percent(diff) < 1, diff);
		// The same flight reads the same everywhere: time from lift-off, and the main band past its opening.
		for (Map<String, Object> r : rows) {
			assertTrue(percent(r.get("difference").toString()) < 2, r.toString());
		}
		assertEquals(Double.parseDouble(rows.get(1).get("simulated").toString().split(" ")[0]),
				Double.parseDouble(rows.get(1).get("measured").toString().split(" ")[0]), 0.1, "time to apogee from lift-off");
		assertTrue(rows.get(3).get("quantity").toString().contains("under main"), rows.get(3).toString());
	}

	private static double percent(String difference) {
		return Double.parseDouble(difference.replaceAll("[^0-9.]", ""));
	}

	@Test
	void apogeeTimeFromTheTopOfAParabola() {
		double[] t = new double[200], h = new double[200];
		for (int i = 0; i < t.length; i++) {
			t[i] = 10 + i * 0.05; // apogee 1000 m at t = 15.3 s, sampled at 20 Hz with +-1 m of quantisation noise
			h[i] = 1000 - 4.9 * (t[i] - 15.3) * (t[i] - 15.3) + (i % 3 - 1);
		}
		int ia = 0;
		for (int i = 1; i < h.length; i++) {
			ia = h[i] > h[ia] ? i : ia;
		}
		assertEquals(15.3, FlightLog.apogeeTime(t, h, ia), 0.03);
	}

	@Test
	void mainBandStartsBelowTheOpening() {
		assertEquals(0.35 * 167.6, FlightLog.mainBand(1320, 167.6)[0], 1e-9);
		assertEquals(5, FlightLog.mainBand(1320, 167.6)[1], 1e-9);
		assertEquals(150, FlightLog.mainBand(1320, Double.NaN)[0], 1e-9, "single deploy: the old band");
		// A falling log at 25 m/s that slows to 4 m/s: the band reads the slow part only.
		double[] t = new double[600], h = new double[600];
		for (int i = 0; i < t.length; i++) {
			t[i] = i * 0.25; // apogee at 0, drogue at 25 m/s, main at 167.6 m slowing over 5 s to 4 m/s
			double open = (1200 - 167.6) / 25;
			h[i] = t[i] <= open ? 1200 - 25 * t[i]
					: t[i] <= open + 5 ? 167.6 - 14.5 * (t[i] - open) : Math.max(0, 167.6 - 72.5 - 4 * (t[i] - open - 5));
		}
		assertEquals(4, FlightLog.metrics(t, h, 167.6).lowerDescent(), 0.05);
	}

	@Test
	@SuppressWarnings("unchecked")
	void accelerometerSeparatesAWeakMotorFromDrag() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		// "Measured" flight: OpenRocket's drag exactly, but the motor gives 10% less thrust.
		Simulation truth = Variants.listen(Variants.of(base, d.doc, null, null), new MonteCarlo.Scaling(1.0, 0.9));
		Variants.runAll(List.of(truth));
		FlightLog.Log log = FlightLog.parse(withAccel(truth), null);
		assertTrue(log.hasAccel(), "accelerometer column found");
		Map<String, Object> out = FlightLog.compare(base, d.doc, log, null, "test");
		Map<String, Object> acc = (Map<String, Object>) out.get("accelerometer");
		assertTrue(acc != null, out.toString());
		double kT = Double.parseDouble(acc.get("thrustFactor").toString().split(" ")[0]);
		double kD = Double.parseDouble(acc.get("dragFactor").toString().split(" ")[0]);
		double coast = Double.parseDouble(acc.get("coastDrag").toString().split(" ")[0]);
		assertEquals(0.9, kT, 0.02, acc.toString());
		assertEquals(1.0, kD, 0.08, acc.toString());
		assertEquals(1.0, coast, 0.08, acc.toString());
		assertTrue(acc.get("reading").toString().startsWith("Mostly the boost"), acc.toString());
		// The apogee-only fit would have blamed drag.
		double apogeeOnly = Double.parseDouble(((Map<?, ?>) out.get("calibration")).get("dragFactor").toString().split(" ")[0]);
		assertTrue(apogeeOnly > 1.1, "apogee alone reads the weak motor as extra drag: " + apogeeOnly);
		// And the climb shows it: the two-factor flight follows the log, the drag-only one does not.
		Map<String, Object> climb = (Map<String, Object>) acc.get("climbRmsError");
		List<Double> rms = climb.values().stream().map(v -> Double.parseDouble(v.toString().split(" ")[0])).toList();
		assertTrue(rms.get(2) < rms.get(1) / 3, climb.toString());
	}

	/** Altimeter CSV plus a 400 Hz axial accelerometer (g, sensor offset -0.2 g, separate clock) from a simulation. */
	static String withAccel(Simulation sim) {
		Branch br = Branch.of(sim.getSimulatedData().getBranch(0));
		double[] t = br.time, h = br.col(info.openrocket.core.simulation.FlightDataType.TYPE_ALTITUDE),
				thrust = br.col(info.openrocket.core.simulation.FlightDataType.TYPE_THRUST_FORCE),
				drag = br.col(info.openrocket.core.simulation.FlightDataType.TYPE_DRAG_FORCE),
				mass = br.col(info.openrocket.core.simulation.FlightDataType.TYPE_MASS);
		StringBuilder sb = new StringBuilder("Time-Accel (s),Accel (Gs),Time (s),Altitude (ft)\n");
		int rows = (int) (t[t.length - 1] * 20) + 40;
		for (int k = 0; k < rows; k++) {
			double ta = k / 400.0 - 0.1, tb = k / 20.0 - 2;
			String acc = "";
			if (ta < 6) {
				double f = ta < 0 ? 1 : (lerp(t, thrust, ta) - lerp(t, drag, ta)) / lerp(t, mass, ta) / 9.80665;
				acc = String.format(Locale.ROOT, "%.4f,%.3f", ta + 0.1, (ta < 0 ? 1 : Math.max(f, ta < 0.05 ? 1 : f)) - 0.2);
			} else {
				acc = ",";
			}
			double alt = tb < 0 ? 0 : lerp(t, h, tb);
			sb.append(acc).append(String.format(Locale.ROOT, ",%.3f,%.2f%n", tb + 2, 300 + alt / 0.3048));
		}
		return sb.toString();
	}

	private static double lerp(double[] x, double[] y, double v) {
		Double r = FlightLog.interpolate(x, y, v);
		return r == null ? (v < x[0] ? y[0] : y[y.length - 1]) : r;
	}
}
