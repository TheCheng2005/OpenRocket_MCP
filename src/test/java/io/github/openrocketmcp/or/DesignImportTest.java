package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonPrimitive;

import info.openrocket.core.document.StorageOptions;
import info.openrocket.core.file.rocksim.export.RockSimSaver;
import info.openrocket.core.logging.ErrorSet;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;

/** Opening designs from elsewhere: every bundled example, RockSim files, and materials carried in the file. */
class DesignImportTest {
	/** Includes designs with catalogue parts ("Deployable payload"), which need the preset DAO bound. */
	@Test
	void everyBundledExampleOpens() throws Exception {
		Designs ds = new Designs();
		for (String name : Designs.EXAMPLES) {
			Designs.Design d = ds.openExample(name);
			assertTrue(d.doc.getRocket().getChildCount() > 0, name);
		}
	}

	/** A RockSim .rkt opens as an import: never written back over the original, saved as .ork on request. */
	@Test
	void rockSimFilesImportAndSaveAsOrk(@TempDir Path tmp) throws Exception {
		Designs ds = new Designs();
		Designs.Design src = ds.openExample("Dual parachute");
		Path rkt = tmp.resolve("legacy.rkt");
		try (OutputStream out = Files.newOutputStream(rkt)) {
			new RockSimSaver().save(out, src.doc, new StorageOptions(), new WarningSet(), new ErrorSet());
		}
		long size = Files.size(rkt);
		Designs.Design d = ds.open(rkt);
		assertNull(d.path, "an import has no .ork of its own yet");
		assertEquals(rkt.toAbsolutePath().normalize(), d.importedFrom);
		assertTrue(d.origin.startsWith("import:"));
		assertNotNull(d.loadWarnings);
		ToolException e = assertThrows(ToolException.class, () -> ds.save(d, null));
		assertTrue(e.getMessage().contains("legacy.ork"), e.getMessage());
		Path ork = ds.save(d, tmp.resolve("legacy.ork"));
		assertTrue(Files.size(ork) > 0);
		assertEquals(size, Files.size(rkt), "the RockSim file is left as it was");
		assertTrue(ds.open(ork).doc.getRocket().getChildCount() > 0, "the saved .ork opens again");
	}

	/** A material that only exists in the design's file (common in older team designs) can be named in an edit. */
	@Test
	void materialsCarriedInTheFileCanBeUsed() throws Exception {
		Designs.Design d = new Designs().openExample("Chute release");
		String custom = null;
		BodyTube tube = null;
		for (RocketComponent c : d.doc.getRocket()) {
			if (c instanceof BodyTube b) {
				tube = b;
				custom = b.getMaterial().getName();
			}
		}
		assertNotNull(tube);
		String result = Components.set(tube, "material", new JsonPrimitive(custom));
		assertTrue(result.contains(custom), result);
	}

	@Test
	void commonStudentFinMaterialsHaveStiffness() {
		Standards std = Standards.defaults();
		for (String m : new String[] { "Balsa", "Basswood", "PLA - 100% infill", "PETG - 100% infill", "ABS - 100% infill" }) {
			assertNotNull(std.shearModulus(m), m);
			assertNotNull(std.materialValue("structures.youngsModulus", m, Dim.PRESSURE), m);
		}
		assertTrue((double) std.shearModulus("Balsa")[0] < (double) std.shearModulus("Basswood")[0]);
	}
}
