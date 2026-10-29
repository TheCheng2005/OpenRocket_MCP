package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;

class TargetsTest {
	@SuppressWarnings("unchecked")
	static Map<String, Object> row(Map<String, Object> out, String id) {
		return ((List<Map<String, Object>>) out.get("targets")).stream().filter(r -> r.get("id").equals(id)).findFirst().orElseThrow();
	}

	static Simulation flown() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		return sim;
	}

	@Test
	void marginsToTheNearerLimit() throws Exception {
		Simulation sim = flown();
		double apogee = sim.getSimulatedData().getMaxAltitude();
		Standards std = Standards.defaults().patched(JsonParser.parseString("{\"targets\": {"
				+ "\"apogee\": {\"min\": " + (apogee - 100) + ", \"max\": " + (apogee + 1000) + ", \"goal\": " + apogee + "},"
				+ "\"railExitVelocity\": {\"min\": \"1000 ft/s\"},"
				+ "\"minStability\": {\"min\": \"0.5 cal\", \"max\": \"40 cal\"}}}").getAsJsonObject());
		Map<String, Object> out = Targets.evaluate(sim, std);

		Map<String, Object> a = row(out, "apogee");
		// Written with units, as the team would: an apogee in kilometres is not checked against part lengths.
		Standards.defaults().patched(JsonParser.parseString("{\"targets\": {\"apogee\": {\"min\": \"3 km\", \"max\": \"30000 ft\"}}}").getAsJsonObject());
		assertEquals("PASS", a.get("status"), a.toString());
		assertEquals(100, (double) a.get("marginSI"), 1e-6, "the minimum is the nearer limit");
		assertTrue(a.get("margin").toString().contains("above the minimum"), a.toString());
		assertEquals("DISTANCE", a.get("dim"));

		assertEquals("FAIL", row(out, "railExitVelocity").get("status"));
		Map<String, Object> s = row(out, "minStability");
		assertNull(s.get("dim"), "stability is in calibers");
		assertEquals(0.5, (double) s.get("minSI"), 1e-9);
		assertTrue(s.get("value").toString().endsWith(" cal"), s.toString());

		// No target: the number alone.
		assertNull(row(out, "maxVelocity").get("status"));
		// Flutter defaults to the team's flutter margin (this example's fins are below it).
		Map<String, Object> fl = row(out, "flutterMargin");
		assertEquals("structures.flutterMinMargin", fl.get("from"));
		assertEquals("FAIL", fl.get("status"));
		assertTrue(fl.get("margin").toString().endsWith("below the minimum"), fl.toString());
		assertTrue(out.get("summary").toString().contains("2 of 4 targets missed"), out.toString());
	}

	@Test
	void closeToALimitWarns() throws Exception {
		Simulation sim = flown();
		double apogee = sim.getSimulatedData().getMaxAltitude();
		Standards std = Standards.defaults().patched(JsonParser.parseString(
				"{\"targets\": {\"apogee\": {\"max\": " + (apogee * 1.02) + "}}}").getAsJsonObject());
		assertEquals("WARN", row(Targets.evaluate(sim, std), "apogee").get("status"));
	}

	@Test
	void refusesUnknownTargetsAndWrongUnits() throws Exception {
		Simulation sim = flown();
		Standards unknown = Standards.defaults().patched(JsonParser.parseString("{\"targets\": {\"apogeee\": {\"min\": 10}}}").getAsJsonObject());
		assertTrue(assertThrows(ToolException.class, () -> Targets.evaluate(sim, unknown)).getMessage().contains("apogeee"));
		Standards wrong = Standards.defaults().patched(JsonParser.parseString("{\"targets\": {\"apogee\": {\"min\": \"10 m/s\"}}}").getAsJsonObject());
		assertTrue(assertThrows(ToolException.class, () -> Targets.evaluate(sim, wrong)).getMessage().contains("apogee.min"));
	}
}
