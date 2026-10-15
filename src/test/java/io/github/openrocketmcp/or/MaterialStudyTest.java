package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/** compare_materials: each material flown and checked, weighed parts kept, missing data said, not guessed. */
class MaterialStudyTest {
	final McpServer server = Main.build(new Context(Standards.defaults()));
	int id;

	JsonObject call(String tool, String args, boolean error) {
		String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}";
		JsonObject r = server.handleLine(line).getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertEquals(error, r.get("isError").getAsBoolean(), tool + ": " + text);
		JsonObject o = new JsonObject();
		o.addProperty("text", text);
		return error ? o : JsonParser.parseString(text).getAsJsonObject();
	}

	static double num(JsonObject o, String key) {
		return Double.parseDouble(o.get(key).getAsString().split(" ")[0]);
	}

	@Test
	void finMaterialsAreFlownAndCheckedInOrderOfStiffness() {
		call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}", false);
		JsonObject r = call("compare_materials", "{\"parts\":[\"fins\"],\"materials\":[\"Fiberglass\",\"Carbon fiber\",\"Aluminum\",\"Plywood (birch)\"]}", false);
		JsonArray opts = r.getAsJsonArray("options");
		Map<String, JsonObject> by = new HashMap<>();
		for (var e : opts) {
			JsonObject o = e.getAsJsonObject();
			by.put(o.get("option").getAsString().replace("current: ", ""), o); // the Maple's fins are carbon
		}
		assertTrue(opts.toString().contains("\"current: Carbon fiber\""), opts.toString());
		assertEquals(4, opts.size(), "the current material (carbon) is the current row, not listed twice: " + by.keySet());
		// Flutter speed goes with the shear modulus: aluminum 26 GPa > carbon 4.5 > fiberglass 2.9 > birch plywood 0.6.
		double al = num(by.get("Aluminum"), "finFlutterMargin"), cf = num(by.get("Carbon fiber"), "finFlutterMargin");
		double pw = num(by.get("Plywood (birch)"), "finFlutterMargin");
		assertTrue(al > cf && cf > pw, al + " > " + cf + " > " + pw);
		// Aluminum fins (2700 kg/m3) weigh more than the carbon ones (1780), plywood (630) less.
		assertTrue(by.get("Aluminum").get("launchMass").getAsString().contains("(+"), by.get("Aluminum").toString());
		assertTrue(by.get("Plywood (birch)").get("launchMass").getAsString().contains("(-"), by.get("Plywood (birch)").toString());
		assertTrue(by.get("Aluminum").get("material").getAsString().startsWith("PASS"), by.get("Aluminum").toString());
		assertTrue(r.get("best").getAsString().length() > 0);
		assertEquals(4, r.getAsJsonArray("materials").size(), "every material's properties with sources");
		String fins = r.getAsJsonArray("parts").get(0).getAsString();
		String mat = call("describe_component", "{\"component\":\"" + fins + "\"}", false).getAsJsonObject("properties").get("material")
				.getAsString();
		assertTrue(mat.startsWith("Carbon fiber"), "the design itself keeps its fins: " + mat);
	}

	@Test
	void missingStrengthIsReportedNotPassed() {
		call("open_design", "{\"path\":\"docs/examples/maple-10k.ork\"}", false);
		JsonObject r = call("compare_materials", "{\"parts\":[\"body tubes\"],\"materials\":[\"Blue tube\",\"Fiberglass\"]}", false);
		for (var e : r.getAsJsonArray("options")) {
			JsonObject o = e.getAsJsonObject();
			if (o.get("option").getAsString().equals("Blue tube")) {
				assertTrue(o.get("material").getAsString().startsWith("UNCHECKED"), o.toString());
			}
		}
		assertTrue(r.has("pressureVessels"));
		String err = call("compare_materials", "{\"materials\":[\"Unobtainium\"]}", true).get("text").getAsString();
		assertTrue(err.contains("Unobtainium") && err.contains("Known"), err);
	}

	@Test
	void aWeighedPartKeepsItsHardwareAndChangesByTheMaterial() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Rocket r = d.doc.getRocket();
		FinSet fins = null;
		for (RocketComponent c : r) {
			if (c instanceof FinSet f) {
				fins = f;
			}
		}
		fins.setMaterial(Components.material(fins, info.openrocket.core.material.Material.Type.BULK, "Fiberglass"));
		double glass = fins.getComponentMass();
		fins.setMassOverridden(true);
		fins.setOverrideMass(glass + 0.2); // the weighed fins include 200 g of epoxy fillets
		MaterialStudy.Selection sel = MaterialStudy.select(r, List.of("fins"));
		List<String> notes = new ArrayList<>();
		MaterialStudy.edit(sel, Components.material(fins, info.openrocket.core.material.Material.Type.BULK, "Aluminum"), Double.NaN, notes)
				.accept(r);
		double expected = glass * 2700 / 1850 + 0.2;
		assertEquals(expected, fins.getOverrideMass(), 1e-6, "geometry x the new density, plus what the weigh-in added");
		assertEquals(1, notes.size(), notes.toString());
	}
}
