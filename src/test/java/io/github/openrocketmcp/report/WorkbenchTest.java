package io.github.openrocketmcp.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.standards.Standards;

/** The interactive view's data and page: complete, consistent, self-contained. */
class WorkbenchTest {
	static Map<String, Object> data;

	@BeforeAll
	static void build() throws Exception {
		Designs ds = new Designs();
		Designs.Design d = ds.openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.ensure(sim);
		data = Workbench.data(d.name(), d.id, sim, Map.of("summary", "ok", "checks", List.of()), Standards.defaults());
	}

	@Test
	@SuppressWarnings("unchecked")
	void everyPartHasAMeshAndProperties() {
		Map<String, Object> rocket = (Map<String, Object>) data.get("rocket");
		List<Map<String, Object>> parts = (List<Map<String, Object>>) rocket.get("parts");
		assertTrue(parts.size() >= 5, "parts: " + parts.size());
		for (Map<String, Object> p : parts) {
			byte[] mesh = Base64.getDecoder().decode((String) p.get("mesh"));
			assertTrue(mesh.length > 0 && mesh.length % 36 == 0, p.get("name") + ": whole triangles of float32");
			assertTrue(((String) p.get("color")).matches("#[0-9a-f]{6}"));
			assertTrue(p.get("props") instanceof Map<?, ?> m && !m.isEmpty(), p.get("name") + " has properties");
			assertFalse(((Map<?, ?>) p.get("props")).keySet().stream().anyMatch(k -> k.toString().startsWith("displayOrder")),
					"OpenRocket's GUI bookkeeping is left out");
		}
		assertTrue(parts.stream().anyMatch(p -> "Motor".equals(p.get("type"))), "the motor is a part too");
		assertTrue((double) rocket.get("cp") > (double) rocket.get("cgLaunch"), "a stable rocket: CP aft of CG");
	}

	@Test
	@SuppressWarnings("unchecked")
	void seriesLineUpWithTime() {
		Map<String, Object> f = (Map<String, Object>) data.get("flight");
		List<Double> t = (List<Double>) f.get("t");
		assertTrue(t.size() > 100 && t.size() <= Workbench.MAX_POINTS + 60, "points: " + t.size());
		for (int i = 1; i < t.size(); i++) {
			assertTrue(t.get(i) >= t.get(i - 1), "time never goes back");
		}
		Map<String, Map<String, Object>> series = (Map<String, Map<String, Object>>) f.get("series");
		for (String k : List.of("altitude", "speed", "vz", "acc", "mach", "stability", "thrust", "drag", "aoa", "mass")) {
			assertTrue(series.containsKey(k), k);
			assertEquals(t.size(), ((List<?>) series.get(k).get("values")).size(), k);
		}
		Map<String, List<Double>> path = (Map<String, List<Double>>) f.get("path");
		for (String k : List.of("east", "north", "up", "theta", "phi", "roll")) {
			assertEquals(t.size(), path.get(k).size(), k);
		}
		assertEquals(t.size(), ((List<?>) f.get("cg")).size());
		double maxAlt = ((List<Double>) series.get("altitude").get("values")).stream().filter(v -> v != null).mapToDouble(v -> v).max()
				.orElse(0);
		double apogee = ((List<Map<String, Object>>) data.get("metrics")).stream().filter(m -> m.get("key").equals("apogee"))
				.mapToDouble(m -> ((Number) m.get("value")).doubleValue()).findFirst().orElse(-1);
		assertEquals(apogee, maxAlt, apogee * 0.002, "the peak survives the thinning");
		List<Map<String, Object>> events = (List<Map<String, Object>>) f.get("events");
		assertTrue(events.stream().anyMatch(e -> e.get("kind").equals("apogee")));
		assertTrue(events.stream().anyMatch(e -> e.get("kind").equals("deploy") && e.containsKey("size")), "canopies have a size");
		// Angle of attack means nothing on the rail (no airspeed): blank until rail clearance.
		double rail = events.stream().filter(e -> e.get("kind").equals("rail")).mapToDouble(e -> ((Number) e.get("t")).doubleValue())
				.findFirst().orElseThrow();
		List<Double> aoa = (List<Double>) series.get("aoa").get("values");
		for (int i = 0; i < t.size() && t.get(i) < rail - 1e-3; i++) {
			assertNull(aoa.get(i), "aoa at t=" + t.get(i));
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void replayIsStagedLikeTheVideo() {
		Map<String, Object> f = (Map<String, Object>) data.get("flight");
		Map<String, Object> anim = (Map<String, Object>) f.get("anim");
		assertTrue(anim != null, "the flight animation's staging comes with the flight");
		// Pacing every 0.1 s of flight (animation seconds per flight second): real time through the burn, the long
		// descent sped up, slowed again round apogee.
		double[] pace = (double[]) anim.get("pace");
		List<Double> t = (List<Double>) f.get("t");
		assertEquals(t.get(t.size() - 1) / 0.1, pace.length, 3);
		assertEquals(1.0, pace[5], 1e-9, "real time half a second into the burn");
		double apogee = (double) anim.get("apogee");
		double atApogee = pace[(int) Math.round(apogee / 0.1)], descent = pace[(pace.length + (int) Math.round(apogee / 0.1)) / 2];
		assertTrue(atApogee >= 0.5 && descent < atApogee, "apogee " + atApogee + ", descent " + descent);
		List<Map<String, Object>> nozzles = (List<Map<String, Object>>) anim.get("nozzles");
		assertFalse(nozzles.isEmpty(), "a nozzle to put the flame at");
		Map<String, Object> rocket = (Map<String, Object>) data.get("rocket");
		double length = (double) rocket.get("length");
		for (Map<String, Object> n : nozzles) {
			double[] at = (double[]) n.get("at");
			assertTrue(at[0] > length / 2 && at[0] <= length + 0.3 && (double) n.get("r") > 0, "nozzle at the aft end: " + at[0]);
		}
		// Sections of the stack between separation joints, end to end; every part flies with one of them.
		List<double[]> sections = (List<double[]>) anim.get("sections");
		assertTrue(sections.size() >= 2, "dual deployment comes apart: " + sections.size());
		for (int i = 1; i < sections.size(); i++) {
			assertEquals(sections.get(i - 1)[1], sections.get(i)[0], 1e-9);
		}
		for (Map<String, Object> p : (List<Map<String, Object>>) rocket.get("parts")) {
			int sec = (Integer) p.get("section");
			assertTrue(sec >= 0 && sec < sections.size(), p.get("name") + " section " + sec);
			assertEquals(0, (Integer) p.get("stageNo"), String.valueOf(p.get("name")));
		}
		assertTrue(((List<?>) anim.get("dropped")).isEmpty(), "a single stage drops nothing");
	}

	@Test
	@SuppressWarnings("unchecked")
	void droppedStageFliesItsOwnTrack() throws Exception {
		Designs ds = new Designs();
		Designs.Design d = ds.openExample("Two stage high power");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.ensure(sim);
		Map<String, Object> two = Workbench.data(d.name(), d.id, sim, Map.of("summary", "ok", "checks", List.of()), Standards.defaults());
		Map<String, Object> f = (Map<String, Object>) two.get("flight");
		List<Map<String, Object>> dropped = (List<Map<String, Object>>) ((Map<String, Object>) f.get("anim")).get("dropped");
		assertEquals(1, dropped.size(), "the booster");
		int track = (Integer) dropped.get(0).get("track"), stage = (Integer) dropped.get(0).get("stage");
		Map<String, Object> booster = ((List<Map<String, Object>>) f.get("stages")).stream().filter(x -> x.get("track").equals(track))
				.findFirst().orElseThrow();
		for (String k : List.of("east", "north", "up", "theta", "phi", "roll", "cg")) {
			assertEquals(((List<?>) booster.get("t")).size(), ((List<?>) booster.get(k)).size(), k);
		}
		List<Map<String, Object>> parts = (List<Map<String, Object>>) ((Map<String, Object>) two.get("rocket")).get("parts");
		assertTrue(parts.stream().anyMatch(p -> p.get("stageNo").equals(stage)), "booster parts");
		for (Map<String, Object> p : parts) {
			assertEquals(p.get("stageNo").equals(stage), p.get("section").equals(-1), p.get("name") + " flies with the booster");
		}
	}

	@Test
	void standalonePageEmbedsTheDataSafely() {
		String html = Workbench.standalone(data);
		Matcher m = Pattern.compile("<script type=\"application/json\" id=\"or-data\">(.*?)</script>", Pattern.DOTALL).matcher(html);
		assertTrue(m.find(), "data block");
		JsonObject back = JsonParser.parseString(m.group(1)).getAsJsonObject();
		assertEquals(((Map<?, ?>) data.get("design")).get("name"), back.getAsJsonObject("design").get("name").getAsString());
		assertFalse(m.group(1).contains("</"), "no closing tag can end the data block early");
		assertFalse(html.contains("<!--OR-DATA-->"));
	}

	@Test
	void viewNeedsNothingFromTheNetwork() {
		String html = Workbench.html();
		assertTrue(html.contains("<!--OR-DATA-->"));
		// Hosts give a view no network by default (CSP connect-src 'none'): scripts, styles and fonts are all inline.
		assertFalse(Pattern.compile("(src|href)\\s*=\\s*\"https?:").matcher(html).find(), "no external resources");
		assertFalse(html.contains("@import"), "no imported styles");
		for (String m : List.of("ui/initialize", "ui/notifications/initialized", "ui/notifications/tool-result", "tools/call",
				"ui/notifications/size-changed", "ui/request-display-mode", "ui/update-model-context", "ui/message", "workbench_data")) {
			assertTrue(html.contains(m), "speaks " + m);
		}
	}
}
