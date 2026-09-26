package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.report.Reports;
import io.github.openrocketmcp.report.SvgTest;
import io.github.openrocketmcp.standards.Standards;

/** The landing map (monte_carlo plotPath) and the flight profile (run_simulation plotPath, report). */
class PlotsTest {
	@TempDir
	Path tmp;

	static int count(String s, String part) {
		return s.split(java.util.regex.Pattern.quote(part), -1).length - 1;
	}

	@Test
	@SuppressWarnings("unchecked")
	void landingMapShowsEveryLandingAndTheEllipse() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		MonteCarlo.Settings st = new MonteCarlo.Settings(30, 4, 2, Double.NaN, Math.toRadians(30), Double.NaN, Math.toRadians(1),
				Math.toRadians(5), Double.NaN, 1, 0, 0, 0, 0);
		Path plot = tmp.resolve("plots/landing.svg");
		Map<String, Object> out = MonteCarlo.run(base, d.doc, st, Standards.defaults(), plot);
		String svg = Files.readString(plot);
		SvgTest.wellFormed(svg);
		Map<String, Map<String, Object>> land = (Map<String, Map<String, Object>>) out.get("landing");
		int landings = land.values().stream().mapToInt(m -> (Integer) m.get("landings")).sum();
		// One dot per landing plus one legend dot per stage; one ellipse per stage.
		assertEquals(landings + land.size(), count(svg, "<circle"));
		assertEquals(land.size(), count(svg, "<ellipse"));
		assertTrue(svg.contains(">pad<") && svg.contains("East of the pad") && svg.contains("North of the pad"));
		assertEquals(plot.toAbsolutePath().toString(), out.get("plot"));
	}

	@Test
	void ellipseMatchesAKnownSpread() {
		// Points on a line along +45 deg: the major axis is at 45 deg and the minor axis is zero.
		List<double[]> pts = new java.util.ArrayList<>();
		for (int i = -10; i <= 10; i++) {
			pts.add(new double[] { i, i });
		}
		double[] e = MonteCarlo.ellipse(pts);
		assertEquals(0, e[0], 1e-9);
		assertEquals(0, e[1], 1e-9);
		double sd = Math.sqrt(2 * (2 * 385.0) / 20); // sd along the line: sqrt(sum((i*sqrt2)^2)/(n-1))
		assertEquals(2 * sd, e[2], 1e-9);
		assertEquals(0, e[3], 1e-6);
		assertEquals(Math.PI / 4, e[4], 1e-9);
	}

	@Test
	void flightProfileDrawsBothStagesAndTheEvents() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		String svg = Reports.profileSvg(sim);
		SvgTest.wellFormed(svg);
		assertEquals(2, count(svg, "<path class=\"series"), "sustainer and booster");
		for (String event : List.of("burnout", "separation", "apogee")) {
			assertTrue(svg.contains(event), event);
		}
		assertTrue(svg.contains("Altitude above the pad"));
	}
}
