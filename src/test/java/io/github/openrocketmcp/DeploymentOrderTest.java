package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/** Recovery and staging set up before any motor is chosen must carry over to the configurations created later. */
class DeploymentOrderTest {
	final McpServer server = Main.build(new Context(Standards.defaults()));

	String call(String tool, String args) {
		JsonObject r = server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}").getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertFalse(r.get("isError").getAsBoolean(), tool + ": " + text);
		return text;
	}

	@Test
	void deploymentSetBeforeTheMotorApplies() {
		call("open_design", "{\"newRocketName\":\"Order\"}");
		String stage = JsonParser.parseString(call("get_design", "{}")).getAsJsonObject().getAsJsonArray("components").get(0)
				.getAsString().split("\\[")[1].substring(0, 8);
		call("add_component", "{\"parent\":\"" + stage + "\",\"type\":\"NoseCone\",\"name\":\"Nose\",\"properties\":{\"length\":\"12 in\",\"aftDiameter\":\"4 in\"}}");
		call("add_component", "{\"parent\":\"" + stage + "\",\"type\":\"BodyTube\",\"name\":\"Body\",\"properties\":{\"length\":\"40 in\",\"outerDiameter\":\"4 in\"}}");
		call("add_component", "{\"parent\":\"Body\",\"type\":\"InnerTube\",\"name\":\"MMT\",\"properties\":{\"length\":\"16 in\",\"outerDiameter\":\"57 mm\",\"axialMethod\":\"BOTTOM\",\"axialOffset\":0}}");
		call("add_component", "{\"parent\":\"Body\",\"type\":\"TrapezoidFinSet\",\"name\":\"Fins\",\"properties\":{\"finCount\":3,\"rootChord\":\"8 in\",\"tipChord\":\"3 in\",\"span\":\"4 in\",\"axialMethod\":\"BOTTOM\",\"axialOffset\":0}}");
		call("add_component", "{\"parent\":\"Body\",\"type\":\"Parachute\",\"name\":\"Main\",\"properties\":{\"diameter\":\"48 in\"}}");
		// No motor yet, so no flight configuration: this used to fail with IndexOutOfBoundsException.
		String set = call("set_deployment", "{\"component\":\"Main\",\"event\":\"altitude\",\"altitude\":\"700 ft\",\"configuration\":\"all\"}");
		assertTrue(set.contains("ALTITUDE") && set.contains("default for new ones"), set);
		call("set_motor", "{\"mount\":\"MMT\",\"motor\":\"3425c0019512b8c67b90eac455bda968\"}");
		String sim = call("run_simulation", "{}");
		assertTrue(sim.contains("\"device\":\"Main\""), "the main deploys in the motor's new configuration: " + sim);
		JsonObject main = JsonParser.parseString(sim).getAsJsonObject().getAsJsonArray("deployments").get(0).getAsJsonObject();
		String agl = main.get("altitudeAGL").getAsString();
		double ft = Double.parseDouble(agl.substring(agl.indexOf('(') + 1, agl.indexOf(" ft")));
		assertTrue(ft > 600 && ft <= 701, "the 700 ft altitude event, on the way down: " + agl);
		String after = main.get("timeAfterApogee").getAsString();
		assertTrue(Double.parseDouble(after.split(" ")[0]) > 10, "well after apogee: " + after);
	}
}
