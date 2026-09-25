package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
	void tableIsKeptWithTheDesign(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("A simple model rocket");
		AeroTable.Table t = AeroTable.parse(RASAERO, "rasaero.csv", 0.0254, true);
		AeroTable.set(d.doc.getRocket(), t);
		java.nio.file.Path ork = designs.save(d, dir.resolve("rocket.ork"));
		java.nio.file.Path side = dir.resolve("rocket.aero.json");
		assertTrue(java.nio.file.Files.exists(side));
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
		assertFalse(java.nio.file.Files.exists(side));
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
