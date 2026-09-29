package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.InternalComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import io.github.openrocketmcp.standards.Standards;

/** export_geometry: a closed, outward-facing STL of the right volume; fin patterns; the CFD run matrix. */
class GeometryTest {
	static String key(double x, double y, double z) {
		return Math.round(x * 1e7) + "," + Math.round(y * 1e7) + "," + Math.round(z * 1e7);
	}

	/** Every directed edge must be matched by its reverse exactly once: closed and consistently oriented. */
	static void assertClosed(List<Geometry.Solid> solids, String what) {
		Map<String, Integer> directed = new HashMap<>();
		for (Geometry.Solid s : solids) {
			for (double[] t : s.tris) {
				for (int v = 0; v < 3; v++) {
					int w = (v + 1) % 3;
					String e = key(t[3 * v], t[3 * v + 1], t[3 * v + 2]) + ">" + key(t[3 * w], t[3 * w + 1], t[3 * w + 2]);
					directed.merge(e, 1, Integer::sum);
				}
			}
		}
		int bad = 0;
		for (Map.Entry<String, Integer> e : directed.entrySet()) {
			String[] ab = e.getKey().split(">");
			if (e.getValue() != 1 || directed.getOrDefault(ab[1] + ">" + ab[0], 0) != 1) {
				bad++;
			}
		}
		assertEquals(0, bad, what + ": open or inconsistently oriented edges");
	}

	/** Signed volume by the divergence theorem (positive for outward normals). */
	static double volume(List<Geometry.Solid> solids) {
		double v = 0;
		for (Geometry.Solid s : solids) {
			for (double[] t : s.tris) {
				v += (t[0] * (t[4] * t[8] - t[5] * t[7]) - t[1] * (t[3] * t[8] - t[5] * t[6]) + t[2] * (t[3] * t[7] - t[4] * t[6])) / 6;
			}
		}
		return v;
	}

	@Test
	void theBodyIsClosedFacesOutAndHasTheRocketsVolume() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		List<Geometry.Solid> all = Geometry.solids(fc, 180);
		List<Geometry.Solid> body = new ArrayList<>(), fins = new ArrayList<>();
		for (Geometry.Solid s : all) {
			(s.tris.isEmpty() ? body : isFin(fc, s.name) ? fins : body).add(s);
		}
		assertTrue(body.size() >= 3, "nose, tube(s) and base");
		assertTrue(body.stream().anyMatch(s -> s.name.equals("base")));
		assertClosed(body, "body");
		double exact = 0;
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (c instanceof SymmetricComponent sc && !(c instanceof InternalComponent)) {
				int n = 4000;
				for (int i = 0; i < n; i++) {
					double r = sc.getRadius(sc.getLength() * (i + 0.5) / n);
					exact += Math.PI * r * r * sc.getLength() / n;
				}
			}
		}
		assertEquals(exact, volume(body), exact * 0.005, "enclosed volume (180 facets lose about 0.02%)");

		String preview = Geometry.previewSvg(all, "t");
		for (Geometry.Solid sd : all) {
			assertTrue(preview.contains(">" + sd.name + "</text>"), "legend lists " + sd.name);
		}
		int drawn = preview.split("<polygon").length - 1, total = all.stream().mapToInt(sd -> sd.tris.size()).sum();
		assertTrue(drawn > total / 3 && drawn < total * 2 / 3, "back faces dropped: " + drawn + " of " + total);
		assertEquals(1, fins.size());
		assertClosed(fins, "fins");
		FinSet f = StudiesTest.first(d, FinSet.class);
		double plates = f.getFinCount() * f.getPlanformArea() * f.getThickness();
		double v = volume(fins);
		assertTrue(v > plates && v < plates * 1.1, "fin plates (root sunk slightly into the body): " + v + " vs " + plates);
	}

	private static boolean isFin(FlightConfiguration fc, String name) {
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (c instanceof FinSet f && Geometry.safe(f.getName()).equals(name)) {
				return true;
			}
		}
		return false;
	}

	@Test
	void finPatternHasThePlanformArea() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FinSet f = StudiesTest.first(d, FinSet.class);
		String dxf = Geometry.dxf(f, 1000);
		assertTrue(dxf.startsWith("0\nSECTION") && dxf.endsWith("EOF\n"));
		String[] lines = dxf.split("\n");
		double area = 0;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].equals("LINE") && lines[i + 2].equals("FIN_OUTLINE")) {
				double x0 = Double.parseDouble(lines[i + 4]), y0 = Double.parseDouble(lines[i + 6]);
				double x1 = Double.parseDouble(lines[i + 10]), y1 = Double.parseDouble(lines[i + 12]);
				area += (x0 * y1 - x1 * y0) / 2;
			}
		}
		double tab = f.getTabHeight() > 1e-9 && f.getTabLength() > 1e-9 ? f.getTabHeight() * f.getTabLength() : 0;
		assertTrue(tab > 0, "this example's fins have a through-the-wall tab, which the pattern includes");
		assertEquals((f.getPlanformArea() + tab) * 1e6, Math.abs(area), 1e-3, "mm^2");
		assertTrue(dxf.contains("ROOT_LINE"), "the body surface is marked on tabbed fins");
	}

	@Test
	void earClippingHandlesConcaveOutlines() {
		// A swept fin with a notch: concave.
		List<double[]> p = List.of(new double[] { 0, 0 }, new double[] { 10, 0 }, new double[] { 8, 3 }, new double[] { 5, 1 },
				new double[] { 3, 6 }, new double[] { 1, 4 });
		double a = 0;
		for (int[] t : Geometry.triangulate(new ArrayList<>(p))) {
			double[] u = p.get(t[0]), v = p.get(t[1]), w = p.get(t[2]);
			double ta = ((v[0] - u[0]) * (w[1] - u[1]) - (v[1] - u[1]) * (w[0] - u[0])) / 2;
			assertTrue(ta > 0, "every triangle counter-clockwise");
			a += ta;
		}
		assertEquals(Geometry.area(new ArrayList<>(p)), a, 1e-9);
	}

	@Test
	void runMatrixFollowsTheFlight() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		List<Geometry.Case> cases = Geometry.cases(sim, fc);
		double maxMach = sim.getSimulatedData().getMaxMachNumber();
		assertTrue(cases.stream().anyMatch(c -> c.name().equals("max_q")));
		assertEquals(maxMach, cases.stream().filter(c -> c.name().equals("max_mach")).findFirst().orElseThrow().mach(), 1e-9);
		double prev = 0;
		for (Geometry.Case c : cases) {
			assertTrue(c.reynolds() > 1e5 && c.velocity() > 0 && c.density() > 0 && c.orCd() > 0, c.toString());
			assertEquals(c.mach() * Math.sqrt(1.4 * 287.05 * c.temperature()), c.velocity(), c.velocity() * 0.01, "V = M a");
			if (c.name().startsWith("M")) {
				assertTrue(c.mach() > prev);
				prev = c.mach();
			}
		}
		assertTrue(prev <= maxMach * 1.1 + 0.05 + 1e-9);
		// Sutherland: 1.716e-5 Pa s at 273.15 K.
		assertEquals(1.716e-5, Geometry.viscosity(273.15), 1e-8);
		String csv = Geometry.casesCsv(cases, new double[] { 0, 2 });
		assertEquals(cases.size() * 2 + 1, csv.split("\n").length);
		assertTrue(Geometry.resultsTemplate(cases).startsWith("Mach,Alpha,CD Power-Off,CD Power-On,CP"));
		// The template parses as an aero table once filled in.
		StringBuilder filled = new StringBuilder("Mach,Alpha,CD Power-Off,CD Power-On,CP\n");
		for (Geometry.Case c : cases) {
			if (c.name().startsWith("M")) {
				filled.append(c.mach()).append(",0,").append(c.orCd()).append(",,").append(c.orCpX()).append('\n');
			}
		}
		AeroTable.Table t = AeroTable.parse(filled.toString(), "cfd", 1.0, true);
		assertTrue(t != null);
	}
}
