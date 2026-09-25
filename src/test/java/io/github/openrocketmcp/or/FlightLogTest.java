package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;

class FlightLogTest {
	/** An altimeter-style CSV (feet, pad at 300 ft MSL, 20 Hz, 2 s of pad data) from a simulation. */
	static String log(Simulation sim) {
		double[][] tr = FlightLog.simTrack(sim);
		StringBuilder sb = new StringBuilder("Time (s),Altitude (ft),Velocity (ft/s)\n");
		for (double t = -2; t < 0; t += 0.05) {
			sb.append(String.format(java.util.Locale.ROOT, "%.2f,%.1f,0%n", t + 2, 300.0));
		}
		for (int i = 0; i < tr[0].length; i++) {
			sb.append(String.format(java.util.Locale.ROOT, "%.3f,%.2f,0%n", tr[0][i] + 2, 300 + tr[1][i] / 0.3048));
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
		io.github.openrocketmcp.report.SvgTest.wellFormed(svg);
		// Same vehicle: apogee difference ~0 and factor ~1
		Simulation same = Variants.of(base, d.doc, null, null);
		Variants.runAll(List.of(same));
		Map<String, Object> o2 = FlightLog.compare(base, d.doc, FlightLog.parse(log(same), null), null, "t");
		double f2 = Double.parseDouble(((Map<?, ?>) o2.get("calibration")).get("dragFactor").toString().split(" ")[0]);
		assertEquals(1.0, f2, 0.03);
		String diff = ((List<Map<String, Object>>) o2.get("comparison")).get(0).get("difference").toString();
		assertTrue(Math.abs(Double.parseDouble(diff.replace("%", ""))) < 1, diff);
	}
}
