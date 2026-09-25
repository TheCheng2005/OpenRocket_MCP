package io.github.openrocketmcp.standards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.UnitSystem;
import io.github.openrocketmcp.units.Units;

class StandardsTest {
	@AfterEach
	void resetUnits() {
		Units.setSystem(UnitSystem.BOTH);
	}

	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void defaultsUseLaunchCanada2027() {
		Standards s = Standards.defaults();
		assertTrue(s.rulesName().contains("2027"), s.rulesName());
		assertEquals(100 * 0.3048, s.rule("railDepartureVelocity.min", Dim.VELOCITY), 1e-6);
		assertEquals(45, s.rule("lengthToDiameter.max", Dim.DIMENSIONLESS), 1e-9);
		assertEquals(18.5 * 0.3048, s.q("launchSite.railLength", Dim.LENGTH, Double.NaN), 1e-9);
		assertTrue(Double.isNaN(s.q("launchSite.altitudeMsl", Dim.DISTANCE, Double.NaN)), "null means unset");
		assertEquals(140, s.pinStrength("4-40 NYLON"), 1e-9, "pin names are case-insensitive");
		assertTrue(Double.isNaN(s.pinStrength("2-56 nylon")));
		assertFalse(s.ruleRef("railDepartureVelocity").isBlank());
	}

	@Test
	void deepMergeKeepsSiblingsAndReplacesLeaves() {
		Standards s = Standards.defaults().patched(json("{\"recovery\":{\"openingForceCoefficient\":1.8,"
				+ "\"shearPins\":{\"2-56 nylon\":{\"strength\":\"75 N\"}}},\"launchSite\":{\"altitudeMsl\":\"1200 ft\"}}"));
		assertEquals(1.8, s.q("recovery.openingForceCoefficient", Dim.DIMENSIONLESS, 0), 1e-12);
		assertEquals(2.0, s.q("recovery.shearPinHoldSafetyFactor", Dim.DIMENSIONLESS, 0), 1e-12, "siblings kept");
		assertEquals(140, s.pinStrength("4-40 nylon"), 1e-9, "existing pins kept");
		assertEquals(75, s.pinStrength("2-56 nylon"), 1e-9, "new pin added");
		assertEquals(1200 * 0.3048, s.q("launchSite.altitudeMsl", Dim.DISTANCE, Double.NaN), 1e-9);
		// the original is untouched
		assertEquals(1.4, Standards.defaults().q("recovery.openingForceCoefficient", Dim.DIMENSIONLESS, 0), 1e-12);
	}

	@Test
	void switchingRuleSets() {
		Standards r4 = Standards.defaults().patched(json("{\"ruleset\":\"launch-canada-r4\"}"));
		assertTrue(Double.isNaN(r4.rule("lengthToDiameter.max", Dim.DIMENSIONLESS)), "L:D is a 2027 edict");
		assertEquals(1.5, r4.rule("stability.minCalibers", Dim.DIMENSIONLESS), 1e-9);
		Standards none = Standards.defaults().patched(json("{\"ruleset\":\"none\"}"));
		assertFalse(none.hasRules());
		assertTrue(Double.isNaN(none.rule("stability.minCalibers", Dim.DIMENSIONLESS)));
		ToolException e = assertThrows(ToolException.class, () -> Standards.defaults().patched(json("{\"ruleset\":\"irec-2031\"}")));
		assertTrue(e.getMessage().contains("launch-canada-2027"));
	}

	@Test
	void teamFileAndCustomRuleSet(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("my-rules.json"), "{\"name\":\"Club rules\",\"stability\":{\"minCalibers\":2.0,\"ref\":\"club 1.1\"}}");
		Files.writeString(dir.resolve("openrocket-mcp.json"), "{\"units\":\"imperial\",\"ruleset\":\"my-rules.json\"}");
		Standards s = Standards.load(dir.resolve("openrocket-mcp.json"));
		assertEquals("Club rules", s.rulesName());
		assertEquals(2.0, s.rule("stability.minCalibers", Dim.DIMENSIONLESS), 1e-12);
		assertEquals("club 1.1", s.ruleRef("stability"));
		assertEquals(UnitSystem.IMPERIAL, Units.system());
		assertTrue(Units.fmt(1, Dim.LENGTH).endsWith("in"));
		// save and reload round trip
		Path out = dir.resolve("saved.json");
		s.save(out);
		assertEquals(s.data(), json(Files.readString(out)));
	}

	@Test
	void badFilesGiveReadableErrors(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("bad.json"), "{ units: ");
		assertTrue(assertThrows(ToolException.class, () -> Standards.load(dir.resolve("bad.json"))).getMessage().contains("Invalid JSON"));
		assertThrows(ToolException.class, () -> Standards.load(dir.resolve("missing.json")));
		Standards s = Standards.defaults().patched(json("{\"recovery\":{\"packingFactor\":\"two\"}}"));
		ToolException e = assertThrows(ToolException.class, () -> s.q("recovery.packingFactor", Dim.DIMENSIONLESS, 1));
		assertTrue(e.getMessage().contains("recovery.packingFactor"));
	}

	@Test
	void shearModulusByMaterialName() {
		Standards s = Standards.defaults();
		assertEquals(2.9e9, (Double) s.shearModulus("Fiberglass")[0], 1);
		assertEquals(2.9e9, (Double) s.shearModulus("G10 (custom)")[0], 1);
		assertEquals(26e9, (Double) s.shearModulus("Aluminum")[0], 1);
		assertEquals(4.5e9, (Double) s.shearModulus("Carbon fiber")[0], 1);
		assertEquals(0.6e9, (Double) s.shearModulus("Plywood (birch)")[0], 1);
		assertNull(s.shearModulus("Balsa"));
		Standards custom = s.patched(json("{\"structures\":{\"shearModulus\":{\"balsa\":\"0.03 GPa\"}}}"));
		assertEquals(0.03e9, (Double) custom.shearModulus("Balsa")[0], 1);
		// A team key that overlaps a default key wins
		Standards team = s.patched(json("{\"structures\":{\"shearModulus\":{\"fiberglass|g10\":{\"value\":\"5 GPa\"}}}}"));
		assertEquals(5e9, (Double) team.shearModulus("Fiberglass")[0], 1);
	}
}
