package io.github.openrocketmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.units.Dim;

/** Errors and result notes tagged by whose problem they are, for the app to show where they belong. */
class IssuesTest {
	McpServer s;

	@BeforeEach
	void setUp() {
		s = new McpServer("test", "0.0.1", "instructions");
		JsonObject schema = Schema.object().qty("thickness", "Fin thickness.", false).str("finSet", "Fin set.", false)
				.enumStr("objective", "Goal.", false, "max_apogee", "min_mass").build();
		s.tool(new ToolDef("parse", "Parse", "Reads a thickness.", schema, true, a -> {
			if (a.has("finSet")) {
				throw new ToolException("No component '" + a.str("finSet") + "'. Use get_design to list component ids.");
			}
			return Map.of("si", a.qty("thickness", Dim.LENGTH));
		}));
		s.tool(new ToolDef("fly", "Fly", "The flight fails.", Schema.object().build(), true, a -> {
			throw ToolException.physics("The simulation never reaches apogee.");
		}));
		s.tool(new ToolDef("fetch", "Fetch", "The network fails.", Schema.object().build(), true, a -> {
			throw ToolException.system("Could not reach the weather service.");
		}));
		s.tool(new ToolDef("crash", "Crash", "A bug.", Schema.object().build(), true, a -> {
			throw new IllegalStateException("boom");
		}));
		s.tool(new ToolDef("notes", "Notes", "A result with problems and a value it could not compute.", Schema.object().build(),
				true, a -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("apogee", 3000.0);
					m.put("deployment", Map.of("airspeed", Double.NaN));
					m.put("PROBLEMS", List.of("Main: simulation stopped at t=0.4 s: the rocket tumbled.",
							"Max Mach 4.2 is beyond OpenRocket's aerodynamics (validated to about Mach 3)."));
					return m;
				}));
		s.tool(new ToolDef("clean", "Clean", "A sound result.", Schema.object().build(), true, a -> Map.of("apogee", 3000.0)));
	}

	JsonObject result(String tool, String args) {
		return s.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}").getAsJsonObject("result");
	}

	static JsonArray issues(JsonObject r) {
		return r.getAsJsonObject("_meta").getAsJsonArray(Issues.META_KEY);
	}

	static JsonObject issue(JsonObject r) {
		assertTrue(r.get("isError").getAsBoolean(), r.toString());
		return issues(r).get(0).getAsJsonObject();
	}

	@Test
	void inputErrorsNameTheirField() {
		JsonObject bad = issue(result("parse", "{\"thickness\":\"abc\"}"));
		assertEquals("input", bad.get("kind").getAsString());
		assertEquals("error", bad.get("severity").getAsString());
		assertEquals("thickness", bad.get("field").getAsString());

		JsonObject choice = issue(result("parse", "{\"objective\":\"fastest\"}"));
		assertEquals("objective", choice.get("field").getAsString(), "'fastest' is not a valid objective");

		JsonObject part = issue(result("parse", "{\"finSet\":\"Wings\"}"));
		assertEquals("finSet", part.get("field").getAsString(), "the quoted value is what was given for finSet");

		JsonObject unknown = issue(result("parse", "{\"thicknes\":\"3 mm\"}"));
		assertEquals("thickness", unknown.get("field").getAsString(), "the argument it probably meant");
	}

	@Test
	void physicsAndSystemErrorsSayWhose() {
		JsonObject fly = issue(result("fly", "{}"));
		assertEquals("physics", fly.get("kind").getAsString());
		assertFalse(fly.has("field"));
		JsonObject net = issue(result("fetch", "{}"));
		assertEquals("system", net.get("kind").getAsString());
		assertFalse(net.has("bug"), "no internet is not a bug");
	}

	@Test
	void bugsGetAnIdAndAReport() {
		JsonObject r = result("crash", "{}");
		JsonObject bug = issue(r);
		assertEquals("system", bug.get("kind").getAsString());
		assertTrue(bug.get("bug").getAsBoolean());
		String id = bug.get("id").getAsString();
		assertTrue(id.matches("[0-9a-f]{8}"), id);
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(text.contains("Error ID " + id), "the model and the user see the same ID: " + text);
		String report = bug.get("report").getAsString();
		assertTrue(report.contains("test 0.0.1") && report.contains("Tool: crash") && report.contains("IllegalStateException: boom")
				&& report.contains("  at "), report);
	}

	@Test
	void resultNotesSortPhysicsFromOutput() {
		JsonObject r = result("notes", "{}");
		assertFalse(r.get("isError").getAsBoolean());
		JsonArray notes = issues(r);
		assertEquals(3, notes.size(), notes.toString());
		JsonObject stopped = notes.get(0).getAsJsonObject();
		assertEquals("physics", stopped.get("kind").getAsString());
		assertEquals("error", stopped.get("severity").getAsString(), "a stopped simulation is an error");
		JsonObject mach = notes.get(1).getAsJsonObject();
		assertEquals("output", mach.get("kind").getAsString(), "beyond the model's range: the number is the problem");
		assertEquals("warning", mach.get("severity").getAsString());
		JsonObject blank = notes.get(2).getAsJsonObject();
		assertEquals("output", blank.get("kind").getAsString());
		assertTrue(blank.get("text").getAsString().contains("deployment.airspeed"), blank.toString());

		// The model reads the same result as before.
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(JsonParser.parseString(text.replace("NaN", "null")).getAsJsonObject().has("PROBLEMS"), text);
	}

	@Test
	void soundResultsCarryNoNotes() {
		JsonObject r = result("clean", "{}");
		assertFalse(r.has("_meta"), r.toString());
	}

	@Test
	void fieldInference() {
		JsonObject schema = Schema.object().qty("thickness", "", false).str("device", "", false).str("parent", "", false).build();
		JsonObject args = JsonParser.parseString("{\"device\":\"Main\",\"parent\":\"Main\"}").getAsJsonObject();
		assertEquals("thickness", Issues.field("thickness must be greater than zero.", schema, null));
		assertNull(Issues.field("No component 'Main'.", schema, args), "two arguments gave 'Main': can't tell which");
		assertNull(Issues.field("Something else went wrong.", schema, args));
	}
}
