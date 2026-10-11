package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.or.Web;
import io.github.openrocketmcp.standards.Standards;

/** parachute_sizes and add_from_link through the server, on the examples' high-power rocket. */
class ProductToolsTest {
	McpServer server;
	int id;
	String design;

	@BeforeEach
	void open() {
		server = Main.build(new Context(Standards.defaults()));
		design = JsonParser.parseString(call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}").get("text").getAsString())
				.getAsJsonObject().get("designId").getAsString();
	}

	@AfterEach
	void network() {
		Web.use(null);
	}

	JsonObject call(String tool, String args) {
		String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}";
		JsonObject r = server.handleLine(line).getAsJsonObject("result");
		JsonObject out = r.getAsJsonArray("content").get(0).getAsJsonObject().deepCopy();
		out.addProperty("isError", r.get("isError").getAsBoolean());
		out.addProperty("parts", r.getAsJsonArray("content").size());
		return out;
	}

	JsonObject ok(String tool, String args) {
		JsonObject r = call(tool, args);
		assertTrue(!r.get("isError").getAsBoolean(), () -> tool + ": " + r);
		return JsonParser.parseString(r.get("text").getAsString()).getAsJsonObject();
	}

	@Test
	void theChartAgreesWithTheSimulationAndSizingFliesTheTarget() {
		JsonObject r = ok("parachute_sizes", "{\"designId\":\"" + design + "\",\"cds\":[0.85,1.5,2.2]}");
		assertEquals(3, r.getAsJsonArray("sizeForTarget").size());
		assertTrue(r.get("otherDrag").getAsString().contains("parachute still attached"), r.toString());
		// The current main's simulated rate is what check_requirements reads.
		String now = r.getAsJsonObject("current").get("simulatedRate").getAsString();
		assertTrue(call("check_requirements", "{\"designId\":\"" + design + "\"}").get("text").getAsString()
				.contains("\"value\":\"" + now.replace(" (over the limit)", "") + "\""), now);
		assertTrue(call("parachute_sizes", "{\"designId\":\"" + design + "\"}").get("parts").getAsInt() >= 1);

		// Size it at Cd 1.5: the re-flown main lands at the 20 ft/s target (rounded up to a whole inch, so a touch under).
		JsonObject sized = ok("parachute_sizes", "{\"designId\":\"" + design + "\",\"cd\":1.5,\"apply\":true,\"catalog\":false}");
		double rate = Double.parseDouble(sized.getAsJsonObject("current").get("simulatedRate").getAsString().split(" ")[0]);
		assertTrue(rate <= 6.096 && rate > 5.9, "m/s: " + rate);
		assertTrue(ok("undo", "{\"designId\":\"" + design + "\"}").toString().contains("parachute_sizes"));
	}

	@Test
	void aProductLinkGoesIntoTheDesign() throws Exception {
		String page = Files.readString(Path.of("src/test/resources/products/chute-jsonld.html"));
		Web.use(new Web.Client() {
			@Override
			public Web.Response get(URI uri) {
				return uri.getPath().equals("/old") ? new Web.Response(301, "/iris-84", "") : new Web.Response(200, null, page);
			}

			@Override
			public Web.Response postJson(URI uri, String json) {
				throw new AssertionError();
			}
		});
		JsonObject r = ok("add_from_link", "{\"designId\":\"" + design + "\",\"url\":\"https://93.184.216.34/old\",\"component\":\"Main\"}");
		assertEquals("parachute", r.getAsJsonObject("product").get("kind").getAsString());
		assertTrue(r.getAsJsonObject("fromThePage").get("cd").getAsString().contains("catalog"), r.toString());
		assertTrue(r.getAsJsonObject("applied").get("descentRate").getAsString().contains("->"), r.toString());

		// A pasted altimeter page goes into the av-bay; the design's mass grows by its weight.
		String alt = Files.readString(Path.of("src/test/resources/products/altimeter.html"));
		JsonObject a = ok("add_from_link", "{\"designId\":\"" + design + "\",\"pageText\":" + new com.google.gson.Gson().toJson(alt) + "}");
		assertTrue(a.getAsJsonObject("applied").get("item").getAsString().contains("Av-bay"), a.toString());

		// apply=false changes nothing.
		String before = call("get_design", "{\"designId\":\"" + design + "\"}").get("text").getAsString();
		ok("add_from_link", "{\"designId\":\"" + design + "\",\"pageText\":" + new com.google.gson.Gson().toJson(alt) + ",\"apply\":false}");
		assertEquals(before, call("get_design", "{\"designId\":\"" + design + "\"}").get("text").getAsString());
	}
}
