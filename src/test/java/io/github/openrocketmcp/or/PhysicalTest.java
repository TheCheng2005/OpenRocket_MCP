package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonPrimitive;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;

/** Non-physical edits are refused, clamped ones are reported as kept, and a flight that cannot happen says so. */
class PhysicalTest {
	static <T> T find(Designs.Design d, Class<T> type) {
		for (RocketComponent c : d.doc.getRocket()) {
			if (type.isInstance(c)) {
				return type.cast(c);
			}
		}
		throw new AssertionError("no " + type.getSimpleName());
	}

	/** The examples' high-power rocket: it has ballast and avionics masses to edit. */
	static Designs.Design maple() throws Exception {
		return new Designs().open(Path.of("docs/examples/maple-10k.ork"));
	}

	static String refused(RocketComponent c, String prop, String value) {
		return assertThrows(ToolException.class, () -> Components.set(c, prop, new JsonPrimitive(value))).getMessage();
	}

	@Test
	void partsCannotHaveNegativeOrMissingSizes() throws Exception {
		Designs.Design d = maple();
		TrapezoidFinSet fins = find(d, TrapezoidFinSet.class);
		double t = fins.getThickness();
		assertTrue(refused(fins, "thickness", "-1 mm").contains("cannot be negative"));
		assertTrue(refused(fins, "thickness", "0 mm").contains("cannot be zero"));
		assertTrue(refused(fins, "rootChord", "1e9 m").contains("far beyond"));
		assertEquals(t, fins.getThickness(), "nothing changed");
		assertTrue(refused(find(d, Parachute.class), "cd", "0").contains("cannot be zero"));
		assertTrue(refused(find(d, MassComponent.class), "mass", "-5 g").contains("cannot be negative"));
		assertTrue(refused(fins, "finCount", "2.5").contains("whole number"));
		// Positions and sweep may be negative.
		Components.set(find(d, MassComponent.class), "axialOffset", new JsonPrimitive("-10 mm"));
		Components.set(fins, "sweep", new JsonPrimitive("-5 mm"));
	}

	@Test
	void whatOpenRocketKeepsIsWhatIsReported() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		BodyTube tube = find(d, BodyTube.class);
		String said = Components.set(tube, "thickness", new JsonPrimitive("1 m"));
		assertTrue(said.contains("asked for") && said.contains("keeps it within"), said);
		assertEquals(tube.getOuterRadius(), tube.getThickness(), 1e-12, "a tube cannot be thicker than its radius");
	}

	@Test
	void aRocketThatCannotLiftOffIsAProblemNotAZeroApogee() throws Exception {
		Designs.Design d = maple();
		find(d, MassComponent.class).setComponentMass(500);
		find(d, MassComponent.class).setMassOverridden(false);
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.ensure(sim);
		var problems = Sims.problems(sim);
		assertTrue(problems.stream().anyMatch(p -> p.contains("without lifting the rocket off")), problems.toString());
		assertTrue(Sims.summarize(sim).containsKey("PROBLEMS"));
		var report = Requirements.check(sim, null, Standards.defaults()).render("x");
		assertTrue(report.toString().contains("Physically sound flight"), report.toString());
	}

	@Test
	void aSoundFlightHasNoProblems() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.ensure(sim);
		assertTrue(Sims.problems(sim).isEmpty(), Sims.problems(sim).toString());
	}
}
