package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;

/** rank_motors on a mount wider than any motor (the airframe itself as the mount, as hybrid designs often have). */
class MotorToolsTest {
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

	@Test
	void aWideMountRanksTheLargestMotorsThatFit() {
		call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}");
		// A 110 mm bore: no motor is that size, so before the fix nothing matched at all.
		call("edit_components", "{\"changes\":[{\"component\":\"Motor mount\",\"properties\":{\"outerDiameter\":\"112 mm\",\"thickness\":\"1 mm\"}}]}");
		JsonObject r = call("rank_motors", "{\"mount\":\"Motor mount\",\"objective\":\"max_apogee\",\"maxLength\":\"1300 mm\",\"maxCandidates\":6,\"limit\":3}");
		assertTrue(r.getAsJsonArray("ranking").size() > 0, r.toString());
		assertTrue(r.get("motorDiameter").getAsString().startsWith("98 mm"), r.get("motorDiameter").getAsString());
	}
}
