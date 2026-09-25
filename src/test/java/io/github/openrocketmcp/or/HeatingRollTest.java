package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.standards.Standards;

class HeatingRollTest {
	@Test
	void heatingFormulas() {
		assertEquals(288.15 * 1.8, Heating.stagnation(288.15, 2), 1e-9, "T0 = T (1 + 0.2 M^2)");
		assertTrue(Heating.recovery(288.15, 2) < Heating.stagnation(288.15, 2));
		assertEquals(288.15, Heating.stagnation(288.15, 0), 0);
		double q = Heating.suttonGraves(1.2, 500, 0.005);
		assertEquals(8 * q, Heating.suttonGraves(1.2, 1000, 0.005), 1e-9 * q, "q ~ V^3");
		assertEquals(q / 2, Heating.suttonGraves(1.2, 500, 0.02), 1e-9 * q, "q ~ 1/sqrt(Rn)");
		// Mach 3 at 20 km (216.65 K): about 606 K stagnation
		assertEquals(606.6, Heating.stagnation(216.65, 3), 0.1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void surfacesComparedWithMaterialLimits() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		List<Map<String, Object>> ok = (List<Map<String, Object>>) Heating.analyze(sim, Standards.defaults(), 0.005).get("surfaces");
		assertTrue(ok.size() >= 3 && ok.stream().allMatch(s -> s.get("status").toString().startsWith("OK")), ok.toString());
		// A (hypothetical) resin that softens at 25 C: the fast subsonic flight exceeds it at the tip
		Standards low = Standards.defaults().patched(JsonParser.parseString(
				"{\"structures\":{\"maxServiceTemperature\":{\"fiberglass\":\"25 C\"}}}").getAsJsonObject());
		List<Map<String, Object>> hot = (List<Map<String, Object>>) Heating.analyze(sim, low, 0.005).get("surfaces");
		assertTrue(hot.stream().anyMatch(s -> s.get("status").toString().startsWith("CHECK")), hot.toString());
	}

	@Test
	void rollScalesWithCantAndDesignIsUntouched() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		List<Roll.Case> cs = Roll.sweep(base, d.doc, null, List.of(0.0, Math.toRadians(0.5), Math.toRadians(1), Math.toRadians(-1)));
		assertTrue(cs.get(0).maxRoll() < 1e-3, "no cant, no roll");
		double half = cs.get(1).maxRoll(), one = cs.get(2).maxRoll();
		assertEquals(2, one / half, 0.2, "roll rate ~ proportional to cant");
		assertEquals(one, cs.get(3).maxRoll(), 0.05 * one, "symmetric in sign");
		for (RocketComponent c : d.doc.getRocket()) {
			if (c instanceof FinSet f) {
				assertEquals(0, f.getCantAngle(), 0, "design not modified");
			}
		}
		Map<String, Object> r = Roll.render(cs, 2, 3);
		assertTrue(r.get("alignmentTolerance").toString().contains("deg"), r.toString());
	}

	@Test
	void resonanceCrossingIsDetectedForLargeCant() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		List<Roll.Case> cs = Roll.sweep(base, d.doc, null, List.of(Math.toRadians(0.1), Math.toRadians(3)));
		assertTrue(Double.isNaN(cs.get(0).crossingTime()), "slow roll stays below the pitch frequency");
		assertTrue(!Double.isNaN(cs.get(1).crossingTime()) && cs.get(1).crossingFreq() > 0, cs.get(1).toString());
	}
}
