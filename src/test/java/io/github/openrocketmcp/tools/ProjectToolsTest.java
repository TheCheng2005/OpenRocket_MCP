package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;

/** The chief engineer's tools: notes checked against the design, and the design's record over time. */
class ProjectToolsTest {
	final McpServer server = Main.build(new Context(Standards.defaults()));
	int id;

	JsonObject call(String tool, String args, boolean error) {
		String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}";
		JsonObject r = server.handleLine(line).getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertEquals(error, r.get("isError").getAsBoolean(), tool + ": " + text);
		if (error) {
			JsonObject o = new JsonObject();
			o.addProperty("text", text);
			return o;
		}
		return JsonParser.parseString(text).getAsJsonObject();
	}

	JsonObject call(String tool, String args) {
		return call(tool, args, false);
	}

	static Map<String, JsonObject> byClaim(JsonObject r) {
		Map<String, JsonObject> m = new HashMap<>();
		for (JsonElement e : r.getAsJsonArray("claims")) {
			m.put(e.getAsJsonObject().get("claim").getAsString(), e.getAsJsonObject());
		}
		return m;
	}

	@Test
	void notesAreCheckedAgainstTheDesign() {
		call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}");
		JsonObject run = call("run_simulation", "{}");
		String apogee = run.getAsJsonObject("flight").get("apogee").getAsString().split(" \\(")[0]; // "2999 m"
		double m = Double.parseDouble(apogee.split(" ")[0]);
		JsonObject r = call("verify_claims", "{\"claims\":["
				+ "{\"quantity\":\"apogee\",\"value\":\"" + apogee + "\",\"source\":\"Oct 12 meeting\"},"
				+ "{\"quantity\":\"Apogee\",\"value\":\"" + Math.round(m * 1.25) + " m\"},"
				+ "{\"quantity\":\"altitude\",\"value\":\"~" + Math.round(m / 0.3048 / 1000) + "k ft\"},"
				+ "{\"quantity\":\"stability\",\"value\":\"at least 1 cal\"},"
				+ "{\"quantity\":\"liftoff mass\",\"value\":\"under 5 lb\"},"
				+ "{\"quantity\":\"apogee\",\"value\":\"10000\"},"
				+ "{\"quantity\":\"warp factor\",\"value\":\"9\"},"
				+ "{\"quantity\":\"motor\",\"value\":\"M1315W\"},"
				+ "{\"component\":\"fins\",\"property\":\"material\",\"value\":\"carbon fiber\"},"
				+ "{\"component\":\"fins\",\"property\":\"material\",\"value\":\"aluminum\"}]}");
		Map<String, JsonObject> c = byClaim(r);
		assertEquals("MATCH", c.get("apogee: " + apogee).get("status").getAsString(), c.toString());
		assertEquals("Oct 12 meeting", c.get("apogee: " + apogee).get("source").getAsString());
		assertEquals("MISMATCH", c.get("Apogee: " + Math.round(m * 1.25) + " m").get("status").getAsString());
		assertTrue(c.get("Apogee: " + Math.round(m * 1.25) + " m").get("difference").getAsString().startsWith("-"), "the design is lower");
		assertEquals("MATCH", c.get("altitude: ~" + Math.round(m / 0.3048 / 1000) + "k ft").get("status").getAsString(), "30k-style values");
		assertEquals("MATCH", c.get("stability: at least 1 cal").get("status").getAsString());
		assertEquals("MISMATCH", c.get("liftoff mass: under 5 lb").get("status").getAsString(), "the Maple is ~25 lb");
		assertTrue(c.get("apogee: 10000").get("why").getAsString().contains("no unit"), "a bare number is not guessed");
		assertTrue(c.get("warp factor: 9").get("why").getAsString().contains("Known"));
		assertEquals("MATCH", c.get("motor: M1315W").get("status").getAsString());
		JsonObject finsCarbon = r.getAsJsonArray("claims").get(8).getAsJsonObject();
		assertEquals("MATCH", finsCarbon.get("status").getAsString(), "'fins' finds the fin set: " + finsCarbon);
		assertEquals("MISMATCH", r.getAsJsonArray("claims").get(9).getAsJsonObject().get("status").getAsString());
		assertTrue(r.get("summary").getAsString().startsWith("10 claim(s)"));
		call("verify_claims", "{\"claims\":[]}", true);
		JsonObject empty = call("verify_claims", "{\"claims\":[{}]}").getAsJsonArray("claims").get(0).getAsJsonObject();
		assertEquals("(empty claim)", empty.get("claim").getAsString());
		assertEquals("UNCHECKED", empty.get("status").getAsString());
		call("verify_claims", "{\"claims\":[\"apogee 10k ft\"]}", true);
	}

	@Test
	void theRecordShowsWhatChanged(@TempDir Path dir) throws Exception {
		Path ork = dir.resolve("maple.ork");
		Files.copy(Path.of("docs/examples/maple-10k.ork"), ork);
		call("open_design", "{\"path\":\"" + ork.toString().replace("\\", "/") + "\"}");
		JsonObject first = call("progress_log", "{\"label\":\"PDR\",\"note\":\"baseline\"}");
		assertTrue(first.get("comparedWith").getAsString().contains("first record"));
		assertTrue(Files.exists(dir.resolve("maple.progress.jsonl")), "kept next to the design");
		JsonObject preview = call("progress_log", "{\"mode\":\"preview\"}");
		assertEquals("no (preview)", preview.get("recorded").getAsString());
		JsonArray same = preview.getAsJsonObject("changes").getAsJsonArray("numbers");
		assertEquals("no change beyond tolerance", same.get(0).getAsString(), same.toString());
		// Thinner fins (their mass is weighed, so the liftoff mass stays): less drag, and the flutter margin drops to a FAIL.
		call("edit_components", "{\"changes\":[{\"component\":\"Fins\",\"properties\":{\"thickness\":\"2 mm\"}}]}");
		JsonObject second = call("progress_log", "{\"note\":\"thinner fins\"}");
		String changes = second.getAsJsonObject("changes").toString();
		assertTrue(changes.contains("Apogee") && changes.contains("flutter"), changes);
		assertTrue(second.getAsJsonObject("changes").getAsJsonArray("worse").toString().contains("FAIL Fin flutter margin (Fins)"), changes);
		assertFalse(changes.contains("Unsaved"), "session state is not progress: " + changes);
		String digest = second.get("digest").getAsString();
		assertTrue(digest.contains("Key numbers") && digest.contains("Changed:") && digest.contains("since"), digest);
		JsonObject vsPdr = call("progress_log", "{\"mode\":\"preview\",\"since\":\"PDR\"}");
		assertTrue(vsPdr.get("comparedWith").getAsString().contains("PDR"));
		assertTrue(call("progress_log", "{\"mode\":\"preview\",\"since\":\"CDR\"}", true).get("text").getAsString().contains("PDR"));
		JsonObject history = call("progress_log", "{\"mode\":\"history\"}");
		assertEquals(2, history.get("records").getAsInt());
		assertEquals("PDR", history.getAsJsonArray("history").get(0).getAsJsonObject().get("label").getAsString());
		// The digest can also go to a file for the team page.
		call("progress_log", "{\"mode\":\"preview\",\"path\":\"" + dir.resolve("update.md").toString().replace("\\", "/") + "\"}");
		assertTrue(Files.readString(dir.resolve("update.md")).contains("engineering status"));
	}
}
