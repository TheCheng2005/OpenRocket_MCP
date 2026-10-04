package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration.DeployEvent;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.simulation.FlightData;
import io.github.openrocketmcp.standards.Standards;

/**
 * Reused flight results are never stale: every kind of edit makes the next tool fly again, including the settings
 * OpenRocket changes without a change event (deployment, motor delays, separation).
 */
class ReuseTest {
	static Designs.Design design() throws Exception {
		return new Designs().openExample("Dual parachute");
	}

	static Simulation sim(Designs.Design d) {
		return Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
	}

	/** Flies, reuses once, applies the edit, and checks the next call flies again. */
	static void flownAgainAfter(Consumer<Designs.Design> edit) throws Exception {
		Designs.Design d = design();
		FlightData first = Sims.ensure(sim(d));
		assertSame(first, Sims.ensure(sim(d)), "nothing changed: reused");
		edit.accept(d);
		assertNotSame(first, Sims.ensure(sim(d)), "changed: flown again");
	}

	static <T> T find(Designs.Design d, Class<T> type) {
		for (RocketComponent c : d.doc.getRocket()) {
			if (type.isInstance(c)) {
				return type.cast(c);
			}
		}
		throw new AssertionError(type.getSimpleName());
	}

	@Test
	void aPartEdit() throws Exception {
		flownAgainAfter(d -> find(d, TrapezoidFinSet.class).setHeight(find(d, TrapezoidFinSet.class).getHeight() * 1.1));
	}

	@Test
	void aDeploymentSetting() throws Exception {
		flownAgainAfter(d -> {
			RecoveryDevice rd = find(d, RecoveryDevice.class);
			var dc = rd.getDeploymentConfigurations().get(sim(d).getFlightConfigurationId());
			dc.setDeployEvent(dc.getDeployEvent() == DeployEvent.APOGEE ? DeployEvent.ALTITUDE : DeployEvent.APOGEE);
		});
	}

	@Test
	void aMotorDelay() throws Exception {
		flownAgainAfter(d -> {
			InnerTube mount = find(d, InnerTube.class);
			MotorConfiguration mc = mount.getMotorConfig(sim(d).getFlightConfigurationId());
			mc.setEjectionDelay(Double.isInfinite(mc.getEjectionDelay()) ? 6 : mc.getEjectionDelay() + 2); // plugged -> 6 s
		});
	}

	@Test
	void theLaunchConditions() throws Exception {
		flownAgainAfter(d -> sim(d).getOptions().setLaunchRodLength(sim(d).getOptions().getLaunchRodLength() + 1));
		flownAgainAfter(d -> sim(d).getOptions().setLaunchIntoWind(!sim(d).getOptions().getLaunchIntoWind()));
		flownAgainAfter(d -> Winds.setGround(sim(d).getOptions(), 7, Double.NaN));
	}

	@Test
	void anAeroTable() throws Exception {
		Designs.Design[] seen = new Designs.Design[1];
		try {
			flownAgainAfter(d -> {
				seen[0] = d;
				AeroTable.set(d.doc.getRocket(), AeroTable.parse("mach,cd\n0.1,0.6\n2,0.7\n", "test", 1, true));
			});
		} finally {
			AeroTable.clear(seen[0].doc.getRocket()); // tables are kept by rocket id: do not leak into other tests
		}
	}

	@Test
	void undoFliesTheRestoredRocket() throws Exception {
		Designs.Design d = design();
		double before = Sims.ensure(sim(d)).getMaxAltitude();
		History.Pending p = d.history.before(d.doc.getRocket());
		find(d, TrapezoidFinSet.class).setHeight(find(d, TrapezoidFinSet.class).getHeight() * 1.5);
		d.history.after(p, d.doc.getRocket(), "bigger fins");
		double edited = Sims.ensure(sim(d)).getMaxAltitude();
		assertNotEquals(before, edited, 1e-6);
		d.history.undo(d.doc.getRocket(), 1);
		assertEquals(before, Sims.ensure(sim(d)).getMaxAltitude(), 1e-9, "the undone rocket, not the edited flight");
	}

	@Test
	void aRunWithOtherListenersIsNotReused() throws Exception {
		Designs.Design d = design();
		Sims.ensure(sim(d));
		FlightData special = Sims.run(sim(d), new AscentOnly());
		assertNotSame(special, Sims.ensure(sim(d)), "an ascent-only run is not the flight");
	}

	@Test
	void windCasesAreReusedUntilSomethingChanges() throws Exception {
		Designs.Design d = design();
		Simulation base = sim(d);
		Sims.ensure(base);
		Simulation w = Variants.windCase(base, d.doc, 8);
		assertSame(w, Variants.windCase(base, d.doc, 8));
		assertNotSame(w, Variants.windCase(base, d.doc, 4), "another wind");
		find(d, TrapezoidFinSet.class).setHeight(find(d, TrapezoidFinSet.class).getHeight() * 1.1);
		assertNotSame(w, Variants.windCase(base, d.doc, 8), "after an edit");
		Simulation w2 = Variants.windCase(base, d.doc, 8);
		base.getOptions().setLaunchRodLength(base.getOptions().getLaunchRodLength() + 1);
		assertNotSame(w2, Variants.windCase(base, d.doc, 8), "after a launch condition change");
		List<Variants.Run> table = Variants.windCases(base, d.doc, List.of(0.0, 8.0));
		assertSame(Variants.windCase(base, d.doc, 8), table.get(1).sim());
	}
}
