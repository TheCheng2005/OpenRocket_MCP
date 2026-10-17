package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;

/** aero_analysis per stage stack, and RASAero tables per stack, through the tools. */
class AeroStageTest {
	final McpServer server = Main.build(new Context(Standards.defaults()));
	int id;

	JsonObject call(String tool, String args) {
		String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}";
		JsonObject r = server.handleLine(line).getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertFalse(r.get("isError").getAsBoolean(), tool + ": " + text);
		return JsonParser.parseString(text).getAsJsonObject();
	}

	static double cal(JsonObject row, String key) {
		return Double.parseDouble(row.get(key).getAsString().split(" ")[0]);
	}

	@Test
	void theSustainerAloneHasItsOwnMarginAndTopSpeed() {
		call("open_design", "{\"example\":\"Two stage high power\"}");
		JsonObject whole = call("aero_analysis", "{\"machs\":[0.3]}");
		assertTrue(whole.get("notes").toString().contains("stage"), "the whole stack points to the per-stage view");
		JsonObject sus = call("aero_analysis", "{\"stage\":\"Sustainer\",\"machs\":[0.3]}");
		assertEquals("Sustainer", sus.get("stack").getAsString());
		assertEquals("after Booster separates", sus.get("when").getAsString());
		assertNotEquals(cal(whole.getAsJsonArray("vsMach").get(0).getAsJsonObject(), "marginBurnout"),
				cal(sus.getAsJsonArray("vsMach").get(0).getAsJsonObject(), "marginBurnout"), "its own fins and CG");
		JsonObject f = sus.getAsJsonObject("inFlight");
		assertTrue(Double.parseDouble(f.get("maxMach").getAsString()) > 0.3 && f.has("atMaxMach") && f.has("minMargin"), f.toString());
		assertTrue(sus.get("importedTable").getAsString().contains("import_aero_table stage"));
		// Default range: just past the stack's top speed in flight.
		JsonArray range = call("aero_analysis", "{\"stage\":\"Sustainer\"}").getAsJsonArray("vsMach");
		double top = Double.parseDouble(range.get(range.size() - 1).getAsJsonObject().get("mach").getAsString());
		assertTrue(top >= Double.parseDouble(f.get("maxMach").getAsString()) && top < 1.0, "range to " + top);
		JsonArray stacks = call("aero_analysis", "{\"stage\":\"all\",\"machs\":[0.5]}").getAsJsonArray("stacks");
		assertEquals(2, stacks.size());
		assertEquals("Sustainer + Booster", stacks.get(0).getAsJsonObject().get("stack").getAsString());
	}

	@Test
	void aRasaeroRunOfTheSustainerIsUsedAfterSeparation() {
		call("open_design", "{\"example\":\"Two stage high power\"}");
		String csv = "Mach,Alpha,CD Power-Off,CD Power-On,CP\\n0.01,0,0.9,0.8,40\\n0.5,0,0.95,0.85,40.5\\n1.0,0,1.3,1.2,42\\n2.0,0,1.1,1.0,43";
		JsonObject imp = call("import_aero_table", "{\"stage\":\"Sustainer\",\"csv\":\"" + csv + "\",\"source\":\"RASAero sustainer\"}");
		assertEquals("after Booster separates", imp.get("when").getAsString());
		JsonObject eff = imp.getAsJsonObject("effect");
		assertTrue(eff.has("minAscentStabilityImportedCp") && eff.getAsJsonObject("atMaxMach").has("marginImportedCp"), eff.toString());
		JsonObject sus = call("aero_analysis", "{\"stage\":\"Sustainer\",\"machs\":[0.5]}");
		JsonObject row = sus.getAsJsonArray("vsMach").get(0).getAsJsonObject();
		assertEquals("0.95", row.get("cdImportedPowerOff").getAsString());
		assertTrue(row.has("cpImported") && row.has("marginBurnoutImportedCp"), row.toString());
		assertFalse(call("aero_analysis", "{\"machs\":[0.5]}").getAsJsonArray("vsMach").get(0).getAsJsonObject().has("cdImportedPowerOff"),
				"the whole vehicle has no table");
		String check = call("check_requirements", "{\"includeWindCase\":false}").toString();
		assertTrue(check.contains("Ascent stability with imported CP (RASAero sustainer), Sustainer"), check);
		assertTrue(call("import_aero_table", "{\"mode\":\"show\"}").getAsJsonArray("tables").size() == 1);
		call("import_aero_table", "{\"mode\":\"clear\",\"stage\":\"Sustainer\"}");
		assertFalse(call("check_requirements", "{\"includeWindCase\":false}").toString().contains("imported CP"));
	}
}
