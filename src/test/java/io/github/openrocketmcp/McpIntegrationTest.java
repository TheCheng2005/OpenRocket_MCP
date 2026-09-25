package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/**
 * Drives the server through JSON-RPC exactly as an MCP client would, on OpenRocket's two-stage example.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpIntegrationTest {
	static McpServer server;
	static int id;
	static final boolean VERBOSE = Boolean.getBoolean("mcp.verbose");

	@BeforeAll
	static void setUp() {
		server = Main.build(new Context(Standards.defaults()));
	}

	static JsonObject rpc(String method, String params) {
		String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
		return server.handleLine(line);
	}

	static String call(String tool, String args) {
		JsonObject r = rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}");
		assertTrue(r.has("result"), () -> "protocol error: " + r);
		JsonObject result = r.getAsJsonObject("result");
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		if (VERBOSE) {
			try {
				Files.writeString(Path.of("build/mcp-transcript.txt"), "=== " + tool + " " + args + "\n" + text + "\n",
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}
		assertFalse(result.get("isError").getAsBoolean(), () -> tool + " failed: " + text);
		return text;
	}

	static String callExpectError(String tool, String args) {
		JsonObject r = rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}");
		JsonObject result = r.getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean());
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	@Test
	@Order(1)
	void handshake() {
		JsonObject init = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
		assertEquals("2025-06-18", init.getAsJsonObject("result").get("protocolVersion").getAsString());
		assertEquals(null, server.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
		JsonObject tools = rpc("tools/list", "{}");
		int n = tools.getAsJsonObject("result").getAsJsonArray("tools").size();
		assertTrue(n >= 25, "tools: " + n);
		assertTrue(rpc("prompts/list", "{}").getAsJsonObject("result").getAsJsonArray("prompts").size() >= 3);
		assertTrue(rpc("resources/read", "{\"uri\":\"openrocket://rules\"}").toString().contains("100 ft/s"));
		assertTrue(rpc("nope", "{}").has("error"));
	}

	@Test
	@Order(2)
	void openAndInspect() {
		String open = call("open_design", "{\"example\":\"Two stage high power\"}");
		assertTrue(open.contains("\"designId\":\"d1\""));
		String design = call("get_design", "{}");
		assertTrue(design.contains("Sustainer") && design.contains("Booster"));
		assertTrue(design.contains("after Booster separates"), "per-stack stability");
		String comp = call("describe_component", "{\"component\":\"Sustainer Drogue\"}");
		assertTrue(comp.contains("diameter") && comp.contains("deployment"));
	}

	@Test
	@Order(3)
	void simulateAndCheck() {
		String sim = call("run_simulation", "{}");
		assertTrue(sim.contains("railExitVelocity") && sim.contains("deployments") && sim.contains("Booster"));
		String check = call("check_requirements", "{}");
		assertTrue(check.contains("Rail departure velocity") && check.contains("drogue descent rate"));
		String data = call("get_flight_data", "{\"variables\":[\"altitude\",\"stability\"],\"maxPoints\":10}");
		assertTrue(data.contains("rows"));
		String err = callExpectError("get_flight_data", "{\"variables\":[\"bogus\"]}");
		assertTrue(err.contains("Unknown flight variable"));
	}

	@Test
	@Order(4)
	void recoveryChain() {
		String rec = call("recovery_analysis", "{\"pinType\":\"4-40 nylon\"}");
		assertTrue(rec.contains("openingLoadInfiniteMass") && rec.contains("shearPinsForLaterBays") && rec.contains("harnessWorkingLoad"), rec);
		String sweep = call("deployment_delay_sweep", "{\"device\":\"Sustainer Drogue\",\"delays\":[0,2,4],\"pinType\":\"4-40 nylon\",\"pinCount\":4}");
		assertTrue(sweep.contains("latestDelayWithinCapacity"));
		String size = call("size_parachute", "{\"device\":\"Sustainer Main\",\"targetDescentRate\":\"20 ft/s\"}");
		assertTrue(size.contains("requiredCdA") && size.contains("presetMatches"));
		String shock = call("opening_shock", "{\"mass\":50,\"velocity\":36,\"cd\":2.2,\"area\":0.636,\"airDensity\":0.86,\"cx\":1.4}");
		assertTrue(shock.contains("1092 N"), shock);
		String bp = call("ejection_charge", "{\"bayDiameter\":\"4 in\",\"bayLength\":\"10 in\",\"pressure\":\"15 psi\",\"safetyFactor\":1}");
		assertTrue(bp.contains("0.97"), bp);
		String fit = call("recovery_bay_fit", "{\"items\":[{\"name\":\"26 in drogue\",\"packedVolume\":\"8.8 in3\"},{\"name\":\"cords\",\"cordLength\":\"252 in\"}],"
				+ "\"bayDiameter\":\"4.343 in\",\"availableLength\":\"11 in\"}");
		assertTrue(fit.contains("7.567 in"), fit);
		call("shear_pins", "{\"holdForce\":\"490 N\",\"pinType\":\"4-40 nylon\"}");
		call("descent_energy", "{\"sections\":[{\"name\":\"nose\",\"mass\":\"4 lb\"},{\"name\":\"booster\",\"mass\":\"12 lb\"}],"
				+ "\"descentRate\":\"20 ft/s\",\"energyLimit\":\"75 ft-lbf\"}");
		call("search_parachutes", "{\"minDiameter\":\"48 in\",\"maxDiameter\":\"72 in\",\"limit\":5}");
	}

	@Test
	@Order(5)
	void motorsAndEditing(@TempDir Path tmp) throws Exception {
		call("search_motors", "{\"maxDiameter\":\"38 mm\",\"certLevel\":\"L1\",\"limit\":5}");
		String design = call("get_design", "{}");
		assertTrue(design.contains("motor mount"), design);
		String rank = call("rank_motors", "{\"mount\":\"Sustainer Motor Mount\",\"objective\":\"max_apogee\",\"maxCandidates\":6}");
		assertTrue(rank.contains("ranking"));
		String custom = call("create_custom_motor", "{\"designation\":\"TestLiquid-K\",\"manufacturer\":\"UTAT\",\"type\":\"liquid\","
				+ "\"diameter\":\"38 mm\",\"length\":\"400 mm\",\"totalMass\":\"1.2 kg\",\"propellantMass\":\"0.5 kg\","
				+ "\"thrustCurve\":[[0,0],[0.1,400],[2.5,380],[2.6,0]],\"propellantCgFromTop\":\"120 mm\",\"dryCgFromTop\":\"260 mm\","
				+ "\"saveTo\":\"" + tmp.resolve("TestLiquid-K.rse").toString().replace("\\", "/") + "\"}");
		assertTrue(custom.contains("TestLiquid-K") && Files.exists(tmp.resolve("TestLiquid-K.rse")));
		call("set_motor", "{\"mount\":\"Sustainer Motor Mount\",\"motor\":\"TestLiquid-K\",\"manufacturer\":\"UTAT\"}");
		call("run_simulation", "{}");
		String edit = call("edit_components", "{\"changes\":[{\"component\":\"Sustainer Drogue\",\"properties\":{\"diameter\":\"30 in\",\"cd\":0.9}}]}");
		assertTrue(edit.contains("0.762 m") || edit.contains("762 mm") || edit.contains("30 in"), edit);
		String sweep = call("sweep", "{\"parameter\":\"windSpeed\",\"values\":[\"0 km/h\",\"30 km/h\"]}");
		assertTrue(sweep.contains("landingDistance"));
		String ambiguous = callExpectError("describe_component", "{\"component\":\"Trapezoidal Fin Set\"}");
		assertTrue(ambiguous.contains("Several components"), ambiguous);
		String finId = firstIdOfType(JsonParser.parseString(call("get_design", "{\"designId\":\"d1\"}")).getAsJsonObject()
				.getAsJsonArray("components"), "TrapezoidFinSet");
		String fins = call("describe_component", "{\"component\":\"" + finId + "\"}");
		assertTrue(fins.contains("height"), fins);
		String before = call("describe_component", "{\"component\":\"" + finId + "\"}");
		call("sweep", "{\"component\":\"" + finId + "\",\"parameter\":\"height\",\"values\":[\"40 mm\",\"60 mm\"]}");
		assertEquals(before, call("describe_component", "{\"component\":\"" + finId + "\"}"), "sweep must restore the property");
		String added = call("add_component", "{\"parent\":\"Sustainer\",\"type\":\"BodyTube\",\"name\":\"Test tube\",\"properties\":{\"length\":\"100 mm\"}}");
		assertTrue(added.contains("Test tube"));
		call("remove_component", "{\"component\":\"Test tube\"}");
		Path out = tmp.resolve("saved.ork");
		call("save_design", "{\"path\":\"" + out.toString().replace("\\", "/") + "\"}");
		assertTrue(Files.size(out) > 1000);
		String reopened = call("open_design", "{\"path\":\"" + out.toString().replace("\\", "/") + "\"}");
		assertTrue(reopened.contains("d2"));
	}

	static String firstIdOfType(com.google.gson.JsonArray lines, String type) {
		for (com.google.gson.JsonElement e : lines) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([0-9a-f]{8})\\] (\\w+):").matcher(e.getAsString());
			if (m.find() && m.group(2).equals(type)) {
				return m.group(1);
			}
		}
		return null;
	}

	@Test
	@Order(6)
	void standardsAndUnits() {
		call("set_units", "{\"system\":\"imperial\"}");
		String shock = call("opening_shock", "{\"mass\":\"110 lb\",\"velocity\":\"100 ft/s\",\"cd\":0.8,\"diameter\":\"60 in\",\"altitudeMsl\":\"1000 ft\"}");
		assertTrue(shock.contains("lbf") && !shock.contains(" N "), shock);
		call("set_units", "{\"system\":\"both\"}");
		call("update_standards", "{\"patch\":{\"launchSite\":{\"altitudeMsl\":\"300 m\"},\"recovery\":{\"shearPins\":{\"2-56 nylon\":{\"strength\":\"35 lbf\"}}}}}");
		String pins = call("shear_pins", "{\"holdForce\":\"100 lbf\",\"pinType\":\"2-56 nylon\"}");
		assertTrue(pins.contains("\"pins\":6"), pins);
		JsonObject std = JsonParser.parseString(call("get_standards", "{}")).getAsJsonObject();
		assertEquals("300 m", std.getAsJsonObject("standards").getAsJsonObject("launchSite").get("altitudeMsl").getAsString());
	}

	@Test
	@Order(7)
	void designFromScratch() {
		String open = call("open_design", "{\"newRocketName\":\"Scratch\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		String design = call("get_design", "{\"designId\":\"" + id + "\"}");
		String stage = firstIdOfType(JsonParser.parseString(design).getAsJsonObject().getAsJsonArray("components"), "AxialStage");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"" + stage + "\",\"type\":\"NoseCone\",\"name\":\"Nose\","
				+ "\"properties\":{\"length\":\"16 in\",\"aftDiameter\":\"4 in\"}}");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"" + stage + "\",\"type\":\"BodyTube\",\"name\":\"Airframe\","
				+ "\"properties\":{\"length\":\"48 in\",\"outerDiameter\":\"4 in\",\"thickness\":\"2 mm\"}}");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"Airframe\",\"type\":\"InnerTube\",\"name\":\"MMT\","
				+ "\"properties\":{\"length\":\"12 in\",\"outerDiameter\":\"42 mm\",\"thickness\":\"1.5 mm\",\"axialMethod\":\"bottom\"}}");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"Airframe\",\"type\":\"TrapezoidFinSet\",\"name\":\"Fins\","
				+ "\"properties\":{\"finCount\":4,\"rootChord\":\"8 in\",\"tipChord\":\"3 in\",\"height\":\"4 in\",\"sweepAngle\":\"40 deg\",\"axialMethod\":\"bottom\"}}");
		call("add_component", "{\"designId\":\"" + id + "\",\"parent\":\"Airframe\",\"type\":\"Parachute\",\"name\":\"Main\","
				+ "\"properties\":{\"diameter\":\"36 in\",\"cd\":0.8}}");
		String motor = call("set_motor", "{\"designId\":\"" + id + "\",\"mount\":\"MMT\",\"motor\":\"J350W\"}");
		assertTrue(motor.contains("J350"), motor);
		String sim = call("run_simulation", "{\"designId\":\"" + id + "\"}");
		assertTrue(sim.contains("apogee"), sim);
		String after = call("get_design", "{\"designId\":\"" + id + "\"}");
		assertTrue(after.contains("MCP - "), "new simulation is stored with the design");
		assertTrue(call("run_simulation", "{\"designId\":\"" + id + "\"}").contains("\"launchRodAngleFromVertical\":\"6 deg\""));
	}

	@Test
	@Order(8)
	void optimizeAndMonteCarlo() {
		String open = call("open_design", "{\"example\":\"Dual parachute\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		JsonObject design = JsonParser.parseString(call("get_design", "{\"designId\":\"" + id + "\"}")).getAsJsonObject();
		String fin = firstIdOfType(design.getAsJsonArray("components"), "TrapezoidFinSet");
		String before = call("describe_component", "{\"designId\":\"" + id + "\",\"component\":\"" + fin + "\"}");

		// Static stability target: fast, no simulation.
		JsonObject stab = JsonParser.parseString(call("optimize", "{\"designId\":\"" + id + "\",\"objective\":\"target_stability\","
				+ "\"targetStability\":2.0,\"variables\":[{\"component\":\"" + fin + "\",\"property\":\"height\",\"min\":\"20 mm\",\"max\":\"120 mm\"}],"
				+ "\"maxEvaluations\":24}")).getAsJsonObject();
		double margin = Double.parseDouble(stab.getAsJsonObject("best").get("launchMargin").getAsString().replace(" cal", ""));
		assertEquals(2.0, margin, 0.05, stab.toString());
		assertEquals(before, call("describe_component", "{\"designId\":\"" + id + "\",\"component\":\"" + fin + "\"}"),
				"optimize without apply must not change the design");

		// Simulated: max apogee with a stability floor.
		String maxAp = call("optimize", "{\"designId\":\"" + id + "\",\"objective\":\"max_apogee\",\"minStability\":1.5,\"minRailExit\":0,"
				+ "\"variables\":[{\"component\":\"" + fin + "\",\"property\":\"height\",\"min\":\"20 mm\",\"max\":\"120 mm\"}],"
				+ "\"maxEvaluations\":16,\"apply\":true}");
		JsonObject mo = JsonParser.parseString(maxAp).getAsJsonObject();
		assertTrue(mo.get("feasible").getAsBoolean(), maxAp);
		double minStab = Double.parseDouble(mo.getAsJsonObject("best").get("minAscentStability").getAsString().replace(" cal", ""));
		assertTrue(minStab >= 1.5, maxAp);
		assertTrue(mo.has("applied"));

		String mc1 = call("monte_carlo", "{\"designId\":\"" + id + "\",\"runs\":24,\"seed\":7}");
		String mc2 = call("monte_carlo", "{\"designId\":\"" + id + "\",\"runs\":24,\"seed\":7}");
		assertTrue(mc1.contains("ellipse2Sigma") && mc1.contains("maxDesignLoad"), mc1);
		assertEquals(mc1.replaceAll("\"elapsed\":\"[^\"]*\"", ""), mc2.replaceAll("\"elapsed\":\"[^\"]*\"", ""),
				"same seed must give the same result even though runs are parallel");
	}

	@Test
	@Order(9)
	void reportsExportAndBayVolume(@TempDir Path tmp) throws Exception {
		String dir = tmp.resolve("review").toString().replace("\\", "/");
		String rep = call("generate_report", "{\"designId\":\"d1\",\"outputDir\":\"" + dir + "\",\"pinType\":\"4-40 nylon\"}");
		assertTrue(rep.contains("report.md"), rep);
		String md = Files.readString(tmp.resolve("review/report.md"));
		assertTrue(md.contains("## 1. Requirement checks") && md.contains("stability-ascent.svg") && md.contains("## 5. Recovery"), md);
		String svg = Files.readString(tmp.resolve("review/stability-ascent.svg"));
		assertTrue(svg.startsWith("<svg") && svg.contains("<path class=\"series\" d=\"M"), svg);
		assertTrue(Files.readAllLines(tmp.resolve("review/flight-data.csv")).size() > 50);
		String csv = tmp.resolve("booster.csv").toString().replace("\\", "/");
		call("export_flight_data", "{\"designId\":\"d1\",\"path\":\"" + csv + "\",\"branch\":\"Booster\",\"variables\":[\"altitude\"]}");
		assertTrue(Files.readString(tmp.resolve("booster.csv")).startsWith("\"Time"));
		String fit = call("recovery_bay_fit", "{\"designId\":\"d1\",\"bayComponent\":\"Nose Cone\",\"items\":[{\"name\":\"chute\",\"packedVolume\":\"20 in3\"}]}");
		assertTrue(fit.contains("availableVolume") && fit.contains("Nose Cone"), fit);
	}
}
