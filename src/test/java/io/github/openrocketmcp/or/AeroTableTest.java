package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;

class AeroTableTest {
	static final String RASAERO = """
			Mach,Alpha,CD,CD Power-Off,CD Power-On,CA Power-Off,CA Power-On,CN,CP
			0.01,0,0.5,0.52,0.45,0.52,0.45,0,40.0
			0.01,2,0.55,0.57,0.5,0.57,0.5,0.4,40.5
			0.5,0,0.48,0.50,0.43,0.50,0.43,0,40.2
			0.5,2,0.53,0.55,0.48,0.55,0.48,0.4,40.6
			1.0,0,0.80,0.85,0.75,0.85,0.75,0,42.0
			""";

	@Test
	void parsesRasaeroExport() {
		AeroTable.Table t = AeroTable.parse(RASAERO, "rasaero.csv", 0.0254, true);
		assertEquals(3, t.mach().length, "one row per Mach (alpha 0 kept)");
		assertEquals(0.52, t.cd(0.01, false), 1e-12);
		assertEquals(0.45, t.cd(0.01, true), 1e-12);
		assertEquals(0.51, t.cd(0.255, false), 1e-9, "linear interpolation");
		assertEquals(0.85, t.cd(3.0, false), 1e-12, "clamped above the table");
		assertEquals(40.2 * 0.0254, t.cpAt(0.5), 1e-12, "CP in inches from the nose tip");
		assertTrue(t.hasCp());
	}

	@Test
	void tableIsKeptWithTheDesign(@TempDir Path dir) throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("A simple model rocket");
		AeroTable.Table t = AeroTable.parse(RASAERO, "rasaero.csv", 0.0254, true);
		AeroTable.set(d.doc.getRocket(), t);
		Path ork = designs.save(d, dir.resolve("rocket.ork"));
		Path side = dir.resolve("rocket.aero.json");
		assertTrue(Files.exists(side));
		AeroTable.clear(d.doc.getRocket());
		Designs.Design again = designs.open(ork);
		AeroTable.Table back = AeroTable.of(again.doc.getRocket());
		assertTrue(back != null && back.source().equals("rasaero.csv") && back.useDrag());
		assertArrayEquals(t.mach(), back.mach());
		assertArrayEquals(t.cdOn(), back.cdOn());
		assertArrayEquals(t.cp(), back.cp());
		// Clearing the table and saving removes the file, so it does not come back.
		AeroTable.clear(again.doc.getRocket());
		designs.save(again, ork);
		assertFalse(Files.exists(side));
	}

	/** A sustainer's own table: OpenRocket's drag until the booster separates, the table's after, on the sustainer only. */
	@Test
	void aStackTableAppliesOnlyOnceTheStagesBelowSeparate() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		var r = d.doc.getRocket();
		FlightConfiguration fc = r.getSelectedConfiguration();
		info.openrocket.core.rocketcomponent.AxialStage sustainer = new java.util.ArrayList<>(r.getStageList()).get(0);
		FlightConfiguration alone = Analysis.stack(fc, 0);
		StringBuilder twice = new StringBuilder("Mach,CD,CP\n");
		for (double m = 0.02; m <= 1.2; m += 0.04) {
			Aero.Point p = Aero.sweep(alone, new double[] { m }).get(0);
			twice.append(m).append(',').append(2 * p.cd()).append(',').append(p.cpX()).append('\n');
		}
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		try {
			AeroTable.set(r, sustainer, AeroTable.parse(twice.toString(), "sustainer 2x", 1, true));
			assertEquals(null, AeroTable.of(r), "the whole vehicle has no table");
			List<Variants.Run> runs = Variants.runAll(List.of(AeroTable.without(Variants.of(base, d.doc, null, null)),
					Variants.of(base, d.doc, null, null)));
			Simulation own = runs.get(0).sim(), table = runs.get(1).sim();
			double sep = AeroTable.window(table, sustainer)[0];
			Branch a = Branch.of(own.getSimulatedData().getBranch(0)), b = Branch.of(table.getSimulatedData().getBranch(0));
			double altA = a.col(info.openrocket.core.simulation.FlightDataType.TYPE_ALTITUDE)[a.index(sep - 0.05)];
			double altB = b.col(info.openrocket.core.simulation.FlightDataType.TYPE_ALTITUDE)[b.index(sep - 0.05)];
			assertEquals(altA, altB, 1e-6 * Math.max(1, altA), "identical until the booster separates");
			assertTrue(table.getSimulatedData().getMaxAltitude() < 0.9 * own.getSimulatedData().getMaxAltitude(),
					"twice the sustainer's drag after separation");
			// The booster's own branch flies on OpenRocket's model: it is not the sustainer stack.
			assertEquals(own.getSimulatedData().getBranch(1).getMaximum(info.openrocket.core.simulation.FlightDataType.TYPE_ALTITUDE),
					table.getSimulatedData().getBranch(1).getMaximum(info.openrocket.core.simulation.FlightDataType.TYPE_ALTITUDE), 0.5);
			// The CP check covers the sustainer's own flight, from separation on.
			AeroTable.Margin m = AeroTable.minMargin(table, AeroTable.of(r, sustainer), Analysis.maxDiameter(alone), sustainer);
			assertTrue(m != null && m.time() >= sep - 1e-9, String.valueOf(m));
			Requirements.Report rep = Requirements.check(table, null, Standards.defaults());
			assertTrue(rep.items.stream().anyMatch(i -> i.get("item").toString().equals("Ascent stability with imported CP (sustainer 2x), Sustainer")),
					rep.items.toString());
		} finally {
			AeroTable.clear(r);
		}
		assertTrue(AeroTable.all(r).isEmpty());
	}

	@Test
	void everyStacksTableIsKeptWithTheDesign(@TempDir Path dir) throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("Two stage high power");
		var r = d.doc.getRocket();
		var sustainer = new java.util.ArrayList<>(r.getStageList()).get(0);
		AeroTable.set(r, AeroTable.parse(RASAERO, "whole.csv", 0.0254, true));
		AeroTable.set(r, sustainer, AeroTable.parse(RASAERO, "sustainer.csv", 0.0254, false));
		Path ork = designs.save(d, dir.resolve("two.ork"));
		assertTrue(Files.readString(dir.resolve("two.aero.json")).contains("\"stage\": \"Sustainer\""));
		AeroTable.clear(r);
		Designs.Design again = designs.open(ork);
		var r2 = again.doc.getRocket();
		assertEquals("whole.csv", AeroTable.of(r2).source());
		AeroTable.Table back = AeroTable.of(r2, new java.util.ArrayList<>(r2.getStageList()).get(0));
		assertTrue(back != null && back.source().equals("sustainer.csv") && !back.useDrag());
		assertArrayEquals(AeroTable.parse(RASAERO, "x", 0.0254, true).cp(), back.cp());
		AeroTable.clear(r2);
	}

	@Test
	void parsesPlainTablesAndRejectsBadOnes() {
		AeroTable.Table t = AeroTable.parse("0.1,0.6\n0.9,0.7\n1.2,0.9\n", "plain", 0.0254, true);
		assertEquals(0.65, t.cd(0.5, false), 1e-12);
		assertFalse(t.hasCp());
		assertThrows(ToolException.class, () -> AeroTable.parse("Speed,Drag\n1,2\n3,4", "x", 1, true));
		assertThrows(ToolException.class, () -> AeroTable.parse("Mach,CD\n0.1,55\n0.5,60", "x", 1, true), "CD out of range");
		assertThrows(ToolException.class, () -> AeroTable.parse("Mach,CD\n0.1,0.5", "x", 1, true));
	}

	/** A table built from OpenRocket's own CD must reproduce OpenRocket's flight: validates the drag override. */
	@Test
	void tableFromOpenRocketsOwnDragReproducesTheFlight() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		StringBuilder csv = new StringBuilder("Mach,CD,CP\n");
		for (double m = 0.02; m <= 1.6; m += 0.02) {
			Aero.Point p = Aero.sweep(fc, new double[] { m }).get(0);
			csv.append(m).append(',').append(p.cd()).append(',').append(p.cpX()).append('\n');
		}
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		try {
			AeroTable.set(d.doc.getRocket(), AeroTable.parse(csv.toString(), "self", 1, true));
			List<Variants.Run> r = Variants.runAll(List.of(AeroTable.without(Variants.of(base, d.doc, null, null)),
					Variants.of(base, d.doc, null, null)));
			double own = r.get(0).sim().getSimulatedData().getMaxAltitude();
			double table = r.get(1).sim().getSimulatedData().getMaxAltitude();
			// Differences: the table is at sea-level Reynolds number and zero AoA; flight is at altitude and small AoA.
			assertEquals(own, table, 0.03 * own, own + " vs " + table);

			// Doubling the drag must cut the apogee substantially.
			StringBuilder twice = new StringBuilder("Mach,CD\n");
			for (double m = 0.02; m <= 1.6; m += 0.1) {
				twice.append(m).append(',').append(2 * Aero.sweep(fc, new double[] { m }).get(0).cd()).append('\n');
			}
			AeroTable.set(d.doc.getRocket(), AeroTable.parse(twice.toString(), "2x", 1, true));
			Simulation doubled = Variants.of(base, d.doc, null, null);
			Sims.run(doubled);
			assertTrue(doubled.getSimulatedData().getMaxAltitude() < 0.85 * own);

			// CP from the table: margin close to OpenRocket's simulated stability.
			AeroTable.Table self = AeroTable.parse(csv.toString(), "self", 1, true);
			AeroTable.Margin m = AeroTable.minMargin(r.get(1).sim(), self, Analysis.maxDiameter(fc));
			assertTrue(m != null && m.min() > 0.5 && m.min() < 10, String.valueOf(m));
			AeroTable.set(d.doc.getRocket(), self);
			Requirements.Report rep = Requirements.check(r.get(1).sim(), null, Standards.defaults());
			assertTrue(rep.items.stream().anyMatch(i -> i.get("item").toString().startsWith("Ascent stability with imported CP")));
		} finally {
			AeroTable.clear(d.doc.getRocket());
		}
		assertEquals(null, AeroTable.of(d.doc.getRocket()));
	}
}
