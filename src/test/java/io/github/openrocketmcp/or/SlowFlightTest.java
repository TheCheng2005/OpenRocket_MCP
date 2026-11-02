package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.report.Workbench;
import io.github.openrocketmcp.standards.Standards;

/** A rocket too heavy to reach 100 ft/s has no ascent stability window, and the view still builds. */
class SlowFlightTest {
	@Test
	void noStabilityWindowBelowHundredFeetPerSecond() throws Exception {
		Designs.Design d = new Designs().openExample("A simple model rocket");
		for (RocketComponent c : d.doc.getRocket().getChild(0).getChildren()) {
			if (c instanceof BodyTube t) {
				MassComponent m = new MassComponent();
				m.setComponentMass(0.4);
				t.addChild(m);
				break;
			}
		}
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Simulation sim = Variants.runAll(List.of(Variants.of(base, d.doc, null, null))).get(0).sim();
		assertTrue(sim.getSimulatedData().getMaxVelocity() < 30.48, "the weighted rocket stays below 100 ft/s");
		assertNull(Sims.ascentStability(sim.getSimulatedData().getBranch(0)));
		Map<String, Object> data = Workbench.data(d.name(), d.id, sim, null, Standards.defaults());
		assertFalse(data.toString().contains("NaN"), "no NaN in the view data");
		Workbench.standalone(data); // threw on NaN before
	}
}
