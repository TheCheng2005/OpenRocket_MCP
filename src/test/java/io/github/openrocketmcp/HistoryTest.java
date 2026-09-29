package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/** undo / redo / history through the real tool registry, and design_status on a finished and an empty design. */
class HistoryTest {
	final McpServer server = Main.build(new Context(Standards.defaults()));

	JsonObject result(String tool, String args) {
		return server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}").getAsJsonObject("result");
	}

	String call(String tool, String args) {
		JsonObject r = result(tool, args);
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertFalse(r.get("isError").getAsBoolean(), tool + ": " + text);
		return text;
	}

	String fins;

	/** Id of the example's fin set, from the design tree. */
	String fins() {
		if (fins == null) {
			for (var e : JsonParser.parseString(call("get_design", "{}")).getAsJsonObject().getAsJsonArray("components")) {
				String line = e.getAsString();
				if (line.toLowerCase().contains("fin") && line.contains("[")) {
					fins = line.split("\\[")[1].substring(0, 8);
					break;
				}
			}
		}
		return fins;
	}

	double span() {
		String t = call("describe_component", "{\"component\":\"" + fins() + "\"}");
		JsonObject props = JsonParser.parseString(t).getAsJsonObject().getAsJsonObject("properties");
		return Double.parseDouble(props.get("height").getAsString().split(" ")[0]);
	}

	@Test
	void editsUndoAndRedo() {
		call("open_design", "{\"example\":\"Dual parachute\"}");
		double s0 = span();
		call("run_simulation", "{}");
		call("check_requirements", "{}");
		assertTrue(call("history", "{}").contains("nothing to undo"), "analysis tools that change nothing add no history");

		call("edit_components", "{\"changes\":[{\"component\":\"" + fins() + "\",\"properties\":{\"height\":\"10 cm\"}}]}");
		call("edit_components", "{\"changes\":[{\"component\":\"" + fins() + "\",\"properties\":{\"height\":\"12 cm\"}}]}");
		assertEquals(120, span(), 0.01);
		String h = call("history", "{}");
		assertTrue(h.contains("1. ") && h.contains("2. ") && h.contains("edit_components") && h.contains("12 cm"), h);

		String u = call("undo", "{}");
		assertTrue(u.contains("12 cm") && u.contains("\"canUndo\":1") && u.contains("\"canRedo\":1"), u);
		assertEquals(100, span(), 0.01);
		call("undo", "{}");
		assertEquals(s0, span(), 0.01, "back to the original fins");
		assertTrue(result("undo", "{}").get("isError").getAsBoolean(), "nothing left to undo");
		call("redo", "{\"steps\":2}");
		assertEquals(120, span(), 0.01);

		call("undo", "{}");
		call("edit_components", "{\"changes\":[{\"component\":\"" + fins() + "\",\"properties\":{\"height\":\"9 cm\"}}]}");
		assertFalse(call("history", "{}").contains("canRedo"), "a new edit clears the redo list");
		assertTrue(call("history", "{}").contains("\"unsavedChanges\":true"));
	}

	@Test
	void statusSaysWhatIsMissingAndWhatToDoNext() {
		String id = JsonParser.parseString(call("open_design", "{\"newRocketName\":\"Blank\"}")).getAsJsonObject().get("designId")
				.getAsString();
		JsonObject st = JsonParser.parseString(call("design_status", "{}")).getAsJsonObject();
		String text = st.toString();
		assertTrue(st.get("readiness").getAsString().startsWith("In progress"), text);
		assertTrue(text.contains("No motor") && text.contains("rank_motors"), text);
		assertTrue(text.contains("No parachute") && text.contains("add_avionics_bay"), text);
		assertTrue(st.getAsJsonArray("nextSteps").size() >= 3);

		call("close_design", "{\"designId\":\"" + id + "\"}");
		call("open_design", "{\"example\":\"Dual parachute\"}");
		JsonObject ok = JsonParser.parseString(call("design_status", "{}")).getAsJsonObject();
		assertTrue(ok.has("ruleCheck") && ok.getAsJsonObject("vehicle").has("apogee"), ok.toString());
		assertFalse(ok.toString().contains("No motor"));
	}
}
