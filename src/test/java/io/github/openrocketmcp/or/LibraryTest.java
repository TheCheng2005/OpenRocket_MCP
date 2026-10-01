package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.openrocketmcp.mcp.ToolException;

/** The design library: folder scan, log matching, search and predicted-vs-measured apogee. */
class LibraryTest {
	/** A climb to {@code apogee} m over 20 s and a 25 m/s descent, in feet with a pad altitude, like an altimeter. */
	static String log(double apogee) {
		StringBuilder b = new StringBuilder("Time (s),Altitude (ft)\n");
		for (double t = 0; t < 120; t += 0.1) {
			double a = t < 1 ? 0 : t < 21 ? apogee * (1 - Math.pow((21 - t) / 20, 2)) : Math.max(0, apogee - (t - 21) * 25);
			b.append(String.format(Locale.ROOT, "%.2f,%.1f%n", t, a / 0.3048 + 300));
		}
		return b.toString();
	}

	/** Two files of the same rocket (same name) in different years, one other rocket, logs and a stray CSV. */
	static Path library(Path root) throws Exception {
		Designs ds = new Designs();
		Designs.Design dual = ds.openExample("Dual parachute");
		Files.createDirectories(root.resolve("2023"));
		Files.createDirectories(root.resolve("2025/dual"));
		ds.save(dual, root.resolve("2023/dual-v1.ork"));
		ds.save(dual, root.resolve("2025/dual/dual-v2.ork"));
		Designs.Design two = ds.openExample("Two stage high power");
		Files.createDirectories(root.resolve("archive"));
		ds.save(two, root.resolve("archive/two-stage.ork"));
		Files.writeString(root.resolve("2025/dual/dual-v2-flight1.csv"), log(600));
		Files.writeString(root.resolve("2025/budget.csv"), "part,mass\nnose,200\n");
		return root;
	}

	@Test
	void scanSummarisesDesignsAndMatchesLogsByFileName(@TempDir Path tmp) throws Exception {
		Library.Scan scan = Library.scan(library(tmp), false);
		assertEquals(3, scan.designs().size());
		Library.Entry v1 = find(scan, "2023/dual-v1.ork"), v2 = find(scan, "2025/dual/dual-v2.ork");
		assertEquals(2023, v1.year());
		assertEquals("path", v1.yearSource());
		assertEquals(2025, v2.year());
		assertTrue(v1.diameter() > 0.05 && v1.diameter() < 0.06, "diameter " + v1.diameter());
		assertTrue(v1.launchMass() > 1 && v1.launchMass() < 3);
		assertEquals("H", v1.motorClass());
		assertTrue(v1.predictedApogee() > 500, "the example carries saved results");
		assertEquals(2, find(scan, "archive/two-stage.ork").stages());

		assertEquals(1, scan.flights().size(), "the budget CSV is not a flight");
		Library.Flight f = scan.flights().get(0);
		assertSame(v2, f.design(), "the log names dual-v2, not the older file of the same rocket");
		assertEquals(600, f.apogee(), 1);
		assertTrue(scan.skipped().stream().anyMatch(s -> s.startsWith("2025/budget.csv")), scan.skipped().toString());

		Map<String, Object> trend = Library.trend(scan, scan.designs());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) trend.get("flights");
		assertEquals(1, rows.size());
		double err = (v2.predictedApogee() - 600) / 600 * 100;
		assertTrue(rows.get(0).get("predictionError").toString().startsWith(err >= 0 ? "+" : "-"));
		assertTrue(trend.get("overall").toString().contains("1 flight"), trend.toString());
	}

	@Test
	void aLogAloneInADesignsFolderBelongsToIt(@TempDir Path tmp) throws Exception {
		library(tmp);
		Files.writeString(tmp.resolve("archive/launch-day.csv"), log(400));
		Library.Scan scan = Library.scan(tmp, false);
		Library.Flight f = scan.flights().stream().filter(x -> x.relPath().equals("archive/launch-day.csv")).findFirst().orElseThrow();
		assertSame(find(scan, "archive/two-stage.ork"), f.design());
		assertEquals("same folder", f.match());
	}

	@Test
	void searchByDiameterMotorClassYearAndText(@TempDir Path tmp) throws Exception {
		Library.Scan scan = Library.scan(library(tmp), false);
		// 4 in rockets: only the two-stage one
		List<Library.Entry> four = Library.filter(scan, q(null, 0.0991, 0.1041, null, 0, 0));
		assertEquals(1, four.size());
		assertEquals("archive/two-stage.ork", four.get(0).relPath());
		assertEquals(2, Library.filter(scan, q(null, Double.NaN, Double.NaN, "H", 0, 0)).size());
		assertEquals(3, Library.filter(scan, q(null, Double.NaN, Double.NaN, "G-J", 0, 0)).size());
		assertEquals(1, Library.filter(scan, q(null, Double.NaN, Double.NaN, null, 2024, 2025)).size());
		assertEquals(2, Library.filter(scan, q("dual fiberglass", Double.NaN, Double.NaN, null, 0, 0)).size());
		assertEquals(1, Library.filter(scan, new Library.Query(null, Double.NaN, Double.NaN, null, 0, 0, 0, true, null)).size());
		assertThrows(ToolException.class, () -> Library.classRange("big"));
		assertEquals(List.of("L", "N"), List.of(Library.classRange("n-l")));
	}

	@Test
	void similarDesignsRankByDiameterMassAndImpulse(@TempDir Path tmp) throws Exception {
		Library.Scan scan = Library.scan(library(tmp), false);
		Library.Entry two = find(scan, "archive/two-stage.ork");
		List<Library.Entry> near = Library.similar(scan, two.diameter(), two.launchMass(), two.totalImpulse(), null);
		assertSame(two, near.get(0));
		List<Library.Entry> others = Library.similar(scan, two.diameter(), two.launchMass(), two.totalImpulse(), two.file());
		assertEquals(2, others.size());
	}

	@Test
	void unchangedFilesAreNotReadAgain(@TempDir Path tmp) throws Exception {
		library(tmp);
		Library.Entry a = find(Library.scan(tmp, false), "2023/dual-v1.ork");
		assertSame(a, find(Library.scan(tmp, false), "2023/dual-v1.ork"));
	}

	@Test
	void aDesignWithoutASimulationIsFlownInDefaultConditions(@TempDir Path tmp) throws Exception {
		Designs ds = new Designs();
		Designs.Design d = ds.openExample("Dual parachute");
		for (int i = d.doc.getSimulationCount() - 1; i >= 0; i--) {
			d.doc.removeSimulation(i);
		}
		ds.save(d, tmp.resolve("bare.ork"));
		Library.Entry quick = Library.scan(tmp, false).designs().get(0);
		assertTrue(Double.isNaN(quick.predictedApogee()));
		assertEquals("no simulation in the file", quick.predictionSource());
		Library.Entry flown = Library.scan(tmp, true).designs().get(0);
		assertTrue(flown.predictedApogee() > 300, "simulated when asked, not served from the cache: " + flown);
		assertTrue(flown.predictionSource().contains("default conditions"));
	}

	@Test
	void unreadableFilesAreListedNotFatal(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("broken.ork"), "not a rocket");
		Library.Scan scan = Library.scan(tmp, false);
		assertEquals(1, scan.designs().size());
		assertTrue(scan.designs().get(0).error() != null);
		assertTrue(scan.skipped().get(0).startsWith("broken.ork"));
		assertThrows(ToolException.class, () -> Library.scan(tmp.resolve("missing"), false));
		assertNull(Library.match(tmp.resolve("x.csv"), scan.designs())[0]);
	}

	@Test
	void yearsComeFromThePathButNotFromMotorNames(@TempDir Path tmp) throws Exception {
		Path f = Files.writeString(tmp.resolve("x.ork"), "");
		assertEquals(2024, Library.year("2024/maple.ork", f)[0]);
		assertEquals(2025, Library.year("old/maple-2025-v2.ork", f)[0]);
		assertEquals(0, Library.year("valkyrie-M2020.ork", f)[1], "M2020 is a motor, not a year");
		assertEquals(0, Library.year("boattail2030mm.ork", f)[1]);
	}

	@Test
	void simulationExportsAndOtherEncodingsAreHandled(@TempDir Path tmp) throws Exception {
		library(tmp);
		// The same flight written by Windows software: Latin-1 degree sign and a byte-order mark.
		byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
		String log = log(600).replace("Time (s)", "Time (s) at 20\u00b0C");
		byte[] body = log.getBytes(java.nio.charset.Charset.forName("windows-1252"));
		byte[] all = new byte[bom.length + body.length];
		System.arraycopy(bom, 0, all, 0, bom.length);
		System.arraycopy(body, 0, all, bom.length, body.length);
		Files.write(tmp.resolve("2025/dual/dual-v2-flight2.csv"), all);
		// A simulation export beside the design is not a flight.
		Files.writeString(tmp.resolve("2025/dual/dual-v2-sim.csv"),
				"\"Time [s]\",\"Altitude [m]\",\"Stability margin calibers\"\n" + log(900).lines().skip(1)
						.map(l -> l + ",2").reduce("", (x, y) -> x + y + "\n"));
		Library.Scan scan = Library.scan(tmp, false);
		assertEquals(2, scan.flights().size(), scan.flights().toString());
		assertTrue(scan.skipped().stream().anyMatch(x -> x.startsWith("2025/dual/dual-v2-sim.csv (simulation")), scan.skipped().toString());
	}

	private static Library.Query q(String text, double min, double max, String cls, int from, int to) {
		return new Library.Query(text, min, max, cls, from, to, 0, false, null);
	}

	private static Library.Entry find(Library.Scan scan, String rel) {
		return scan.designs().stream().filter(e -> e.relPath().equals(rel)).findFirst().orElseThrow();
	}
}
