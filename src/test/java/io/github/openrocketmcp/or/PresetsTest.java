package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.ToolException;

class PresetsTest {
	@Test
	void typeAliases() {
		assertEquals(ComponentPreset.Type.BODY_TUBE, Presets.type("body tube"));
		assertEquals(ComponentPreset.Type.NOSE_CONE, Presets.type("nosecone"));
		assertEquals(ComponentPreset.Type.RAIL_BUTTON, Presets.type("rail-button"));
		assertEquals(ComponentPreset.Type.TUBE_COUPLER, Presets.type("coupler"));
		assertThrows(ToolException.class, () -> Presets.type("wing"));
	}

	@Test
	void diameterFilterAndSorting() {
		OrRuntime.init();
		double lo = 0.098, hi = 0.104;
		List<ComponentPreset> tubes = Presets.search(ComponentPreset.Type.BODY_TUBE, lo, hi, Double.NaN, Double.NaN, null, null);
		assertFalse(tubes.isEmpty());
		double prev = 0;
		for (ComponentPreset p : tubes) {
			double od = Presets.outerDiameter(p);
			assertTrue(od >= lo - 1e-4 && od <= hi + 1e-4, Presets.name(p));
			assertTrue(od >= prev - 1e-12);
			prev = od;
		}
		List<ComponentPreset> text = Presets.search(ComponentPreset.Type.NOSE_CONE, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
				null, "ogive");
		assertTrue(text.stream().allMatch(p -> Presets.render(p).toString().toLowerCase().contains("ogive")));
	}

	@Test
	void applyingAPresetChangesTheComponent() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		BodyTube tube = null;
		for (RocketComponent c : d.doc.getRocket()) {
			if (c instanceof BodyTube b) {
				tube = b;
				break;
			}
		}
		ComponentPreset p = Presets.search(ComponentPreset.Type.BODY_TUBE, 0.098, 0.104, Double.NaN, Double.NaN, null, null).get(0);
		Presets.apply(tube, Presets.name(p));
		assertEquals(Presets.outerDiameter(p), tube.getOuterRadius() * 2, 1e-6);
		assertEquals(p, tube.getPresetComponent());
		assertThrows(ToolException.class, () -> Presets.find(ComponentPreset.Type.BODY_TUBE, "no such part 123"));
	}
}
