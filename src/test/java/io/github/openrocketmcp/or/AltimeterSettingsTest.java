package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.standards.Standards;

/** Altimeter settings from the flight: backups, Mach lockout, static ports and the card. */
class AltimeterSettingsTest {
	static final AltimeterSettings.Options OPTS = new AltimeterSettings.Options(1, 30.48, 0.7, 1);

	@SuppressWarnings("unchecked")
	@Test
	void maple(@TempDir Path tmp) throws Exception {
		Designs.Design d = new Designs().open(Path.of("docs/examples/maple-10k.ork"));
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		Map<String, Object> out = AltimeterSettings.sheet(sim, OPTS, 0.002, "bay", tmp.resolve("card.md"));
		String lock = ((Map<String, Object>) out.get("lockout")).get("machLockout").toString();
		SensorSim.Trace tr = SensorSim.trace(sim, null, 200, 0, 0);
		double expected = Math.ceil(SensorSim.baroWindow(tr, 0.7)[1] + 1);
		assertTrue(lock.startsWith((int) expected + " s after launch"), lock + " vs " + expected);
		List<Map<String, Object>> ch = (List<Map<String, Object>>) out.get("channels");
		Map<String, Object> main = ch.stream().filter(c -> c.get("device").equals("Main")).findFirst().orElseThrow();
		assertTrue(main.get("backup").toString().startsWith("274.3 m"), main.toString());
		assertTrue(main.containsKey("simulated"), "matched to its simulated opening");
		Map<String, Object> drogue = ch.stream().filter(c -> c.get("device").equals("Drogue")).findFirst().orElseThrow();
		assertEquals("apogee + 1 s", drogue.get("backup"));
		assertTrue(out.get("staticPorts").toString().startsWith("bay volume 122 in3"), out.get("staticPorts").toString());
		String card = Files.readString(tmp.resolve("card.md"));
		assertTrue(card.contains("| Drogue | apogee | apogee + 1 s |") && card.contains("Mach lockout"), card);
	}

	@SuppressWarnings("unchecked")
	@Test
	void eachStagesChutesFollowTheirOwnFlight() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		Map<String, Object> out = AltimeterSettings.sheet(sim, OPTS, 0, null, null);
		List<Map<String, Object>> ch = (List<Map<String, Object>>) out.get("channels");
		Map<String, Object> booster = ch.stream().filter(c -> c.get("device").toString().startsWith("Booster")).findFirst().orElseThrow();
		assertTrue(booster.get("simulated").toString().endsWith("(Booster)"), booster.toString());
		Map<String, Object> sus = ch.stream().filter(c -> c.get("device").equals("Sustainer Drogue")).findFirst().orElseThrow();
		assertTrue(!booster.get("airspeedAtBackup").equals(sus.get("airspeedAtBackup")), "each from its own branch");
		assertTrue(((Map<String, Object>) out.get("lockout")).get("machLockout").toString().startsWith("not needed"));
	}
}
