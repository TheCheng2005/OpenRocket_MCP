package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RocketComponent;

/** mass_budget: reading budgets, matching parts, comparing and applying them to the design. */
class MassBudgetTest {
	@Test
	void readsFlexibleCsv() {
		List<MassBudget.Item> items = MassBudget.parseCsv("Part,Mass (oz),CG (in),Status,Qty,Section\n"
				+ "\"Nose cone, with tip\",10,6,measured,,\nRail button,0.2,,estimated,2,\nUpper section,40,,measured,,yes\nTOTAL,60,,,,\n",
				"g", "mm");
		assertEquals(3, items.size(), "the total row is skipped");
		assertEquals("Nose cone, with tip", items.get(0).part());
		assertEquals(10 * 0.028349523125, items.get(0).mass(), 1e-9);
		assertEquals(6 * 0.0254, items.get(0).cg(), 1e-9);
		assertEquals(2 * 0.2 * 0.028349523125, items.get(1).mass(), 1e-9, "qty multiplies");
		assertTrue(items.get(2).section());
		List<MassBudget.Item> semi = MassBudget.parseCsv("component;mass\nFins;250 g\nNose;0.4 kg\n", "g", "mm");
		assertEquals(0.25, semi.get(0).mass(), 1e-12);
		assertEquals(0.4, semi.get(1).mass(), 1e-12, "units in the cells win");
		assertEquals(0.123, MassBudget.parseCsv("name,weight\nx,123\n", "g", "mm").get(0).mass(), 1e-12, "bare numbers: grams");
	}

	@Test
	void matchesPartsByIdNameAndSimilarName() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		var r = d.doc.getRocket();
		NoseCone nose = StudiesTest.first(d, NoseCone.class);
		assertEquals(nose, MassBudget.match(r, nose.getName()).component());
		assertEquals(nose, MassBudget.match(r, Components.shortId(nose)).component());
		assertEquals(nose, MassBudget.match(r, nose.getName().toUpperCase() + "  ").component(), "case and spaces");
		assertNull(MassBudget.match(r, "flux capacitor").component());
	}

	@Test
	@SuppressWarnings("unchecked")
	void comparesAppliesAndUndoes() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		NoseCone nose = StudiesTest.first(d, NoseCone.class);
		FinSet fins = StudiesTest.first(d, FinSet.class);
		BodyTube tube = (BodyTube) fins.getParent();
		MassBudget.ModelMasses before = new MassBudget.ModelMasses(fc);
		String template = MassBudget.template(fc);
		assertTrue(template.startsWith("part,component_id,mass (g)") && template.contains(nose.getName()));
		// The template reads back as a budget equal to the model.
		Map<String, Object> same = MassBudget.run(d, fc, MassBudget.parseCsv(template, "g", "mm"),
				new MassBudget.Options(Double.NaN, 0, false, true));
		for (Map<String, Object> row : (List<Map<String, Object>>) same.get("items")) {
			assertEquals("ok", row.get("flag"), row.toString());
		}

		// The example ships with mass overrides (a weighed rocket); start from the plain model so the numbers below are
		// the parts' own. Hidden-by-a-section-override is covered in finSetOverrideLandsOnTheWholeSet.
		for (RocketComponent c : d.doc.getRocket()) {
			c.setMassOverridden(false);
			c.setCGOverridden(false);
		}
		before = new MassBudget.ModelMasses(fc);
		double noseNew = before.own(nose) * 1.3, finsNew = before.own(fins) * 0.8, tubeSection = before.subtree(tube) + 0.05;
		List<MassBudget.Item> budget = List.of(
				new MassBudget.Item(nose.getName(), noseNew, 0.05, false, "measured", false, null, true),
				new MassBudget.Item(fins.getName(), finsNew, Double.NaN, false, "measured", false, null, true),
				new MassBudget.Item(tube.getName(), tubeSection, Double.NaN, false, "measured", true, null, true,
						Components.shortId(tube)),
				new MassBudget.Item("Camera", 0.035, Double.NaN, false, "estimated", false, nose.getName(), true));
		Map<String, Object> out = MassBudget.run(d, fc, budget, new MassBudget.Options(2.0, 0.1, true, true));
		MassBudget.ModelMasses after = new MassBudget.ModelMasses(fc);
		assertEquals(noseNew, after.own(nose), 1e-9);
		assertEquals(tubeSection, after.subtree(tube), 1e-9, "the weighed section, fins inside it included");
		assertTrue(nose.isCGOverridden() && Math.abs(nose.getOverrideCGX() - 0.05) < 1e-12);
		assertTrue(tube.isMassOverridden() && tube.isSubcomponentsOverriddenMass());
		RocketComponent camera = null;
		for (RocketComponent c : nose.getChildren()) {
			if (c.getName().equals("Camera")) {
				camera = c;
			}
		}
		assertNotNull(camera, "a part the design lacked is added in its parent");
		assertEquals(0.035, ((MassComponent) camera).getComponentMass(), 1e-12);
		Map<String, Object> tot = (Map<String, Object>) out.get("totals");
		assertTrue(tot.containsKey("margin") && tot.containsKey("projectedDryWithContingency"), tot.toString());
		String finsRow = ((List<Map<String, Object>>) out.get("items")).stream().filter(x -> x.get("part").equals(fins.getName()))
				.findFirst().orElseThrow().toString();
		assertTrue(finsRow.contains("inside the weighed section"), "not counted twice: " + finsRow);
		assertTrue(out.get("change").toString().contains("->"));
		// Two components share the name "Body tube" in this example: by name alone the line is not applied.
		Map<String, Object> amb = MassBudget.run(d, fc, List.of(new MassBudget.Item(tube.getName(), 0.3, Double.NaN, false, "", false,
				null, true)), new MassBudget.Options(Double.NaN, 0, false, true));
		assertTrue(amb.toString().contains("ambiguous"), amb.toString());
	}

	@Test
	void weighedSectionsAreCountedOnce() throws Exception {
		// Per-component masses (used by the mass budget and structural_loads) must add up to OpenRocket's own structure
		// mass when a section's mass is overridden for everything in it.
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		for (RocketComponent c : d.doc.getRocket()) {
			c.setMassOverridden(false);
		}
		RocketComponent tube = StudiesTest.first(d, FinSet.class).getParent();
		tube.setMassOverridden(true);
		tube.setOverrideMass(0.5);
		tube.setSubcomponentsOverriddenMass(true);
		MassBudget.ModelMasses mm = new MassBudget.ModelMasses(fc);
		assertEquals(0.5, mm.subtree(tube), 1e-9, "the section weighs its override");
		assertEquals(info.openrocket.core.masscalc.MassCalculator.calculateStructure(fc).getMass(), mm.dry(), 1e-9,
				"parts add up to OpenRocket's structure mass");
	}

	@Test
	@SuppressWarnings("unchecked")
	void finSetOverrideLandsOnTheWholeSet() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		FinSet fins = StudiesTest.first(d, FinSet.class);
		RocketComponent owner = MassBudget.sectionOwner(fins);
		if (owner != null) {
			// The example weighs the fin can as a section: say so rather than pretend the fins changed.
			Map<String, Object> hidden = MassBudget.run(d, fc, List.of(new MassBudget.Item(fins.getName(), 0.2, Double.NaN, false, "", false,
					null, true)), new MassBudget.Options(Double.NaN, 0, true, true));
			assertTrue(hidden.get("applied").toString().contains("overrides the mass of everything inside it"), hidden.toString());
			owner.setMassOverridden(false);
		}
		MassBudget.run(d, fc, List.of(new MassBudget.Item(fins.getName(), 0.2, Double.NaN, false, "", false, null, true)),
				new MassBudget.Options(Double.NaN, 0, true, true));
		assertEquals(0.2, new MassBudget.ModelMasses(fc).own(fins), 1e-9, "all fins together weigh what the scale said");
	}
}
