package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.standards.Standards;

class RequirementsTest {
	static Optional<Map<String, Object>> item(Requirements.Report r, String prefix) {
		return r.items.stream().filter(i -> String.valueOf(i.get("item")).startsWith(prefix)).findFirst();
	}

	static Sims.Overrides rail(double length) {
		return new Sims.Overrides(Double.NaN, Double.NaN, Double.NaN, length, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
				Double.NaN, Double.NaN, null, Double.NaN);
	}

	@Test
	void railLengthDrivesRailExitVerdict() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Standards std = Standards.defaults();
		Simulation shortRail = Sims.prepare(d, null, null, rail(0.3), std, false);
		Sims.run(shortRail);
		Requirements.Report r = Requirements.check(shortRail, null, std);
		assertEquals("FAIL", item(r, "Rail departure velocity").orElseThrow().get("status"));
		assertTrue(r.render("x").get("summary").toString().contains("failure"));
		// Every item carries the fields the model relies on
		for (Map<String, Object> i : r.items) {
			assertTrue(i.containsKey("status") && i.containsKey("item") && i.containsKey("requirement") && i.containsKey("value"), i.toString());
		}
		assertTrue(r.manual != null && !r.manual.isEmpty(), "2027 manual checklist");
	}

	@Test
	void chuteOpeningDuringAscentFails() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Standards std = Standards.defaults();
		var fcid = d.doc.getRocket().getSelectedConfiguration().getId();
		for (RocketComponent c : d.doc.getRocket()) {
			if (c instanceof RecoveryDevice rd) {
				DeploymentConfiguration dc = rd.getDeploymentConfigurations().get(fcid).copy(fcid);
				dc.setDeployEvent(DeploymentConfiguration.DeployEvent.LAUNCH);
				dc.setDeployDelay(1.5);
				rd.getDeploymentConfigurations().set(fcid, dc);
				break;
			}
		}
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), std, false);
		Sims.run(sim);
		Requirements.Report r = Requirements.check(sim, null, std);
		assertTrue(r.items.stream().anyMatch(i -> "FAIL".equals(i.get("status")) && i.get("item").toString().contains("before apogee")),
				r.items.toString());
	}

	@Test
	void ruleSetNoneOnlyChecksTeamStandards() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Standards none = Standards.defaults().patched(com.google.gson.JsonParser.parseString("{\"ruleset\":\"none\"}").getAsJsonObject());
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), none, false);
		Sims.run(sim);
		Requirements.Report r = Requirements.check(sim, null, none);
		assertTrue(item(r, "Rail departure velocity").isEmpty());
		assertTrue(item(r, "Fin flutter margin").isPresent(), "flutter is a team standard, independent of the rule set");
	}

	@Test
	void stabilityFloorCombinesCalibersAndBodyLength() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		var fc = d.doc.getRocket().getSelectedConfiguration();
		double[] f = Requirements.stabilityFloor(Standards.defaults(), fc);
		assertEquals(Math.max(f[1], f[2] / 100 * f[3]), f[0], 1e-12);
		assertEquals(Dynamics.lengthToDiameter(fc), f[3], 1e-12);
		assertTrue(Requirements.floorText(f).contains("cal"));
		Standards none = Standards.defaults().patched(com.google.gson.JsonParser.parseString("{\"ruleset\":\"none\"}").getAsJsonObject());
		assertEquals(0, Requirements.stabilityFloor(none, fc)[0], 0);
	}
}
