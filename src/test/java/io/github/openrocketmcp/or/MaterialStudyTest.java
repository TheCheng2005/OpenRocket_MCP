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
		// One letter is not a material name (it used to match the first material containing it).
		assertTrue(call("compare_materials", "{\"materials\":[\"x\"]}", true).get("text").getAsString().contains("Known"));
		// No parts listed: the fins, as when parts is left out (it used to fail with an internal error).
		assertEquals("Fins", call("compare_materials", "{\"parts\":[],\"materials\":[\"Fiberglass\"]}", false)
				.getAsJsonArray("parts").get(0).getAsString());
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

	@Test
	void aPartWeighedLighterThanModelledScales() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Rocket r = d.doc.getRocket();
		info.openrocket.core.rocketcomponent.NoseCone nose = null;
		for (RocketComponent c : r) {
			if (c instanceof info.openrocket.core.rocketcomponent.NoseCone n) {
				nose = n;
			}
		}
		double model = nose.getComponentMass();
		nose.setMassOverridden(true);
		nose.setOverrideMass(model / 2); // the real shell is half the modelled weight
		MaterialStudy.Selection sel = MaterialStudy.select(r, List.of("nose cone"));
		MaterialStudy.edit(sel, null, nose.getThickness() / 2, new ArrayList<>()).accept(r);
		assertEquals(nose.getComponentMass() / 2, nose.getOverrideMass(), 1e-9, "half the wall, half the weighed mass");
	}

	@Test
	void theAirframeIsNoseTubesAndTransitions() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Rocket r = d.doc.getRocket();
		info.openrocket.core.rocketcomponent.Transition boattail = new info.openrocket.core.rocketcomponent.Transition();
		boattail.setName("Boattail");
		r.getChild(0).addChild(boattail);
		MaterialStudy.Selection all = MaterialStudy.select(r, List.of("airframe"));
		assertTrue(all.names().contains("Boattail") && all.names().size() >= 3, all.names().toString());
		assertEquals(MaterialStudy.Group.AIRFRAME, all.group());
		assertEquals(List.of("Boattail"), MaterialStudy.select(r, List.of("transitions")).names(), "the nose cone is not a transition here");
	}

	/** Shell buckling against hand calculations (NASA SP-8007 cylinder, SP-8019 cone), fiberglass 18 GPa, 2 mm wall. */
	@Test
	void shellBucklingMatchesTheNasaFormulas() {
		MaterialStudy.Props glass = new MaterialStudy.Props("Fiberglass", 1850, 2.9e9, 18e9, 240e6, 403, 0.12, List.of());
		info.openrocket.core.rocketcomponent.BodyTube tube = new info.openrocket.core.rocketcomponent.BodyTube(1, 0.057, 0.002);
		// gamma = 1 - 0.901 (1 - exp(-sqrt(27.5)/16)) = 0.7482; 0.7482 x 0.605 x 18 GPa x 2 / 55 = 296.3 MPa.
		assertEquals(296.29e6, MaterialStudy.shellBuckling(tube, glass, 0.002, 0.055), 0.3e6);
		info.openrocket.core.rocketcomponent.NoseCone nose = new info.openrocket.core.rocketcomponent.NoseCone(
				info.openrocket.core.rocketcomponent.Transition.Shape.CONICAL, 0.43, 0.057);
		// alpha = atan(57/430); P = 0.33 2 pi E t^2 cos^2(alpha) / sqrt(3 (1 - 0.12^2)) = 85.34 kN over 2 pi 57 mm x 2 mm.
		assertEquals(119.11e6, MaterialStudy.shellBuckling(nose, glass, 0.002, 0.057), 0.2e6);
	}
}
