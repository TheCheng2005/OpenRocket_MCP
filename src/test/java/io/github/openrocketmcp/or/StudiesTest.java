package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.standards.Standards;

/** compare_shapes, recovery_sections and structural_loads. */
class StudiesTest {
	static Simulation run(Designs.Design d) {
		Simulation s = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(s);
		return s;
	}

	static <T> T first(Designs.Design d, Class<T> type) {
		for (RocketComponent c : d.doc.getRocket()) {
			if (type.isInstance(c)) {
				return type.cast(c);
			}
		}
		throw new AssertionError();
	}

	// ------------------------------------------------------------------------------------------- shapes

	@Test
	@SuppressWarnings("unchecked")
	void shapeStudyCoversEveryOptionAndLeavesTheDesignAlone() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		NoseCone nose = first(d, NoseCone.class);
		var shapeBefore = nose.getShapeType();
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Map<String, Object> out = Shapes.study(base, d.doc, nose, null, true, Double.NaN, Double.NaN);
		List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("options");
		// current + the other noses + the other fin edges (+ best combination when both improve)
		assertTrue(rows.size() >= 1 + (Shapes.NOSES.size() - 1) + 2 && rows.size() <= 1 + (Shapes.NOSES.size() - 1) + 2 + 1, rows.size() + "");
		assertEquals(rows.size(), rows.stream().map(r -> r.get("nose") + "|" + r.get("finEdges")).distinct().count(), "no duplicates");
		Map<String, Object> cur = rows.stream().filter(r -> r.get("nose").toString().contains("(current)")
				&& r.get("finEdges").toString().contains("(current)")).findFirst().orElseThrow();
		assertEquals("0%", cur.get("vsCurrent"));
		for (Map<String, Object> r : rows) {
			assertFalse(r.containsKey("error"), r.toString());
			double pct = Double.parseDouble(r.get("vsCurrent").toString().replace("%", ""));
			assertTrue(Math.abs(pct) < 25, "shape changes move apogee by percent, not tens of percent: " + r);
		}
		assertTrue(out.containsKey("bestMeetingStability"));
		assertEquals(shapeBefore, nose.getShapeType(), "design not modified");
	}

	@Test
	void airfoiledFinsHaveLessDragThanSquareOnes() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		List<Shapes.Row> rows = Shapes.evaluate(base, d.doc, List.of(
				new Shapes.Option("n", "square", r -> Shapes.applyFins(r, FinSet.CrossSection.SQUARE)),
				new Shapes.Option("n", "airfoil", r -> Shapes.applyFins(r, FinSet.CrossSection.AIRFOIL))), 0.5);
		assertTrue(rows.get(1).cdDesign() < rows.get(0).cdDesign(), rows.toString());
		assertTrue(rows.get(1).apogee() > rows.get(0).apogee());
	}

	@Test
	void noseLengthsMultiplyTheOptions() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		NoseCone nose = first(d, NoseCone.class);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) Shapes.study(base, d.doc, nose,
				List.of(nose.getLength(), 1.5 * nose.getLength()), false, 0.5, Double.NaN).get("options");
		assertEquals(1 + 2 * Shapes.NOSES.size() - 1, rows.size(), "the current nose at the current length is not repeated");
	}

	// ------------------------------------------------------------------------------------------- sections

	@Test
	void sectionMassesAddUpToTheSimulatedLandingMass() throws Exception {
		for (String ex : new String[] { "Dual parachute", "Two stage high power" }) {
			Designs.Design d = new Designs().openExample(ex);
			Simulation sim = run(d);
			FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
			List<Sections.Section> ss = Sections.of(fc, null);
			for (FlightDataBranch b : sim.getSimulatedData().getBranches()) {
				double sum = ss.stream().filter(s -> s.stage().getName().equals(b.getName())).mapToDouble(Sections.Section::mass).sum();
				double landing = Branch.of(b).last(FlightDataType.TYPE_MASS);
				assertEquals(landing, sum, 0.01 * landing, ex + " / " + b.getName());
			}
		}
	}

	@Test
	void automaticJointsOpenAtTheForwardEndOfEachBay() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		List<Sections.Section> ss = Sections.of(fc, null);
		for (Sections.Section s : ss) {
			for (int i = 1; i < s.pieces().size(); i++) {
				RocketComponent inner = s.pieces().get(i);
				boolean bay = false;
				for (RocketComponent c : inner) {
					bay |= c instanceof info.openrocket.core.rocketcomponent.RecoveryDevice;
				}
				assertFalse(bay, "a bay starts a new section: " + inner.getName());
			}
		}
		// Naming joints replaces the rule: no joints -> one section per stage
		List<Sections.Section> one = Sections.of(fc, Set.of("no such piece"));
		assertEquals(fc.getActiveStageCount(), one.size());
		assertNotEquals(ss.size(), one.size());
	}

	@Test
	@SuppressWarnings("unchecked")
	void sectionReportHasEnergyAndBays() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = run(d);
		Map<String, Object> out = Sections.analyze(sim, Standards.defaults(), null);
		List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("sections");
		assertEquals(3, rows.size(), "nose / main bay + avionics / drogue bay + fin can");
		assertTrue(rows.stream().allMatch(r -> r.get("landingEnergy").toString().contains("within")));
		assertTrue(rows.stream().anyMatch(r -> r.containsKey("bays")));
		assertTrue(rows.stream().allMatch(r -> r.containsKey("landingEnergyIfMainFails")));
		for (Map<String, Object> r : rows) {
			for (Map<String, Object> b : (List<Map<String, Object>>) r.getOrDefault("bays", List.of())) {
				assertTrue(b.get("fill").toString().endsWith("%"), b.toString());
			}
		}
	}

	// ------------------------------------------------------------------------------------------- loads

	@Test
	void loadsSatisfyRigidBodyEquilibrium() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		double[] motor = new double[2];
		List<Loads.Point> pts = Loads.structure(fc, motor);
		double launch = info.openrocket.core.masscalc.MassCalculator.calculateLaunch(fc).getMass();
		assertEquals(launch, pts.stream().mapToDouble(Loads.Point::m).sum() + motor[0], 1e-6 * launch);
		Loads.Aero a = Loads.aero(fc, 0.5);
		double tail = fc.getLength() + 0.1;
		// Past the tail the whole vehicle is "forward": axial force = thrust; bending vanishes (free-free body).
		assertEquals(300, Loads.axial(pts, motor[0], motor[1], 300, 40, a, 5000, tail), 1e-9);
		double peak = 0;
		for (double x = 0.05; x < fc.getLength(); x += 0.05) {
			peak = Math.max(peak, Math.abs(Loads.moment(pts, motor[0], motor[1], a, 20000, Math.toRadians(3), x)));
		}
		assertTrue(peak > 0.1);
		assertEquals(0, Loads.moment(pts, motor[0], motor[1], a, 20000, Math.toRadians(3), tail), 1e-9 * peak + 1e-9);
		assertEquals(0, Loads.moment(pts, motor[0], motor[1], a, 20000, Math.toRadians(3), -0.01), 1e-12, "nothing forward of the tip");
		// Moment scales linearly with alpha and q
		double m1 = Loads.moment(pts, motor[0], motor[1], a, 20000, 0.02, 0.5), m2 = Loads.moment(pts, motor[0], motor[1], a, 40000, 0.04, 0.5);
		assertEquals(4 * m1, m2, 1e-9 * Math.abs(m2));
	}

	@Test
	@SuppressWarnings("unchecked")
	void loadsCoverEveryStackPhase() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		Simulation sim = run(d);
		Map<String, Object> out = Loads.analyze(sim, 30 / 3.6, 2, 200e6);
		List<String> phases = (List<String>) out.get("phases");
		assertEquals(2, phases.size(), phases.toString());
		List<Map<String, Object>> joints = (List<Map<String, Object>>) out.get("joints");
		assertEquals(Loads.joints(d.doc.getRocket().getSelectedConfiguration()).size(), joints.size());
		for (Map<String, Object> j : joints) {
			assertTrue(j.containsKey("maxAxialCompression") && j.containsKey("bendingMomentMaxQGust") && j.containsKey("wallStress"), j.toString());
			assertTrue(j.get("margin").toString().contains("OK"), j.toString());
		}
		// Staging time from the flight
		double sep = sim.getSimulatedData().getBranch(0).getFirstEvent(FlightEvent.Type.STAGE_SEPARATION).getTime();
		assertTrue(phases.get(1).contains("after separation"));
		assertTrue(sep > 0);
	}
}
