package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.database.motor.ThrustCurveMotorSet;
import io.github.openrocketmcp.standards.Standards;

/** Default ejection delays: a NaN delay (listed by some catalogue entries for "plugged") makes OpenRocket's run fail. */
class MotorDelayTest {
	@Test
	void everyMotorGetsARealOrPluggedDelay() {
		int n = 0, plugged = 0;
		for (ThrustCurveMotorSet set : OrRuntime.motors().getMotorSets()) {
			for (ThrustCurveMotor m : set.getMotors()) {
				double d = Motors.defaultDelay(m);
				assertFalse(Double.isNaN(d), m.getDesignation());
				assertTrue(d >= 0, m.getDesignation());
				n++;
				if (d == Motor.PLUGGED_DELAY) {
					plugged++;
				}
			}
		}
		assertTrue(n > 1000 && plugged > 0, n + " motors, " + plugged + " plugged");
	}

	@Test
	void aMotorWithoutListedDelaysSimulates() {
		// AeroTech K1499N lists no standard delays; set_motor used to store a NaN delay and every run then failed with
		// "EJECTION_CHARGE event has a NaN time".
		var server = io.github.openrocketmcp.Main.build(new io.github.openrocketmcp.tools.Context(Standards.defaults()));
		java.util.function.BiFunction<String, String, String> call = (tool, args) -> {
			var r = server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
					+ "\",\"arguments\":" + args + "}}").getAsJsonObject("result");
			String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
			assertFalse(r.get("isError").getAsBoolean(), tool + ": " + text);
			return text;
		};
		call.apply("open_design", "{\"newRocketName\":\"K\"}");
		String stage = com.google.gson.JsonParser.parseString(call.apply("get_design", "{}")).getAsJsonObject()
				.getAsJsonArray("components").get(0).getAsString().split("\\[")[1].substring(0, 8);
		call.apply("add_component", "{\"parent\":\"" + stage + "\",\"type\":\"NoseCone\",\"name\":\"Nose\",\"properties\":{\"length\":\"16 in\",\"aftDiameter\":\"4 in\"}}");
		call.apply("add_component", "{\"parent\":\"" + stage + "\",\"type\":\"BodyTube\",\"name\":\"Body\",\"properties\":{\"length\":\"50 in\",\"outerDiameter\":\"4 in\"}}");
		call.apply("add_component", "{\"parent\":\"Body\",\"type\":\"InnerTube\",\"name\":\"MMT\",\"properties\":{\"length\":\"26 in\",\"outerDiameter\":\"78 mm\",\"axialMethod\":\"BOTTOM\",\"axialOffset\":0}}");
		call.apply("add_component", "{\"parent\":\"Body\",\"type\":\"TrapezoidFinSet\",\"name\":\"Fins\",\"properties\":{\"finCount\":4,\"rootChord\":\"9 in\",\"tipChord\":\"3 in\",\"span\":\"5 in\",\"axialMethod\":\"BOTTOM\",\"axialOffset\":0}}");
		call.apply("add_component", "{\"parent\":\"Body\",\"type\":\"Parachute\",\"name\":\"Main\",\"properties\":{\"diameter\":\"60 in\"}}");
		call.apply("set_deployment", "{\"component\":\"Main\",\"event\":\"apogee\",\"configuration\":\"all\"}");
		String set = call.apply("set_motor", "{\"mount\":\"MMT\",\"motor\":\"e9f65603bda5a680b9524091909c7188\"}");
		assertTrue(set.contains("K1499N"), set);
		String sim = call.apply("run_simulation", "{}");
		assertTrue(sim.contains("\"apogee\""), sim);
	}
}
