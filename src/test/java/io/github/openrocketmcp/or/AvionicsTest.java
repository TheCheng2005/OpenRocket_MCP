package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.MassComponent.MassComponentType;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.mcp.ToolException;

/** add_avionics_bay: a dual-deploy bay laid out by the rules, as typed OpenRocket parts. */
class AvionicsTest {
	static final double IN = Avionics.IN;

	/** A plain 4 in rocket: nose, upper airframe with the main, lower airframe with the drogue. */
	static Designs.Design rocket() {
		Designs.Design d = new Designs().create("Bay test");
		AxialStage stage = (AxialStage) d.doc.getRocket().getChild(0);
		NoseCone nose = new NoseCone();
		nose.setLength(18 * IN);
		nose.setAftRadius(2.01 * IN);
		nose.setAftShoulderLength(4 * IN);
		nose.setAftShoulderRadius(1.95 * IN);
		stage.addChild(nose);
		Components.set(nose, "material", new JsonPrimitive("Fiberglass"));
		for (String[] t : new String[][] { { "Upper airframe", "24" }, { "Lower airframe", "40" } }) {
			BodyTube b = new BodyTube();
			b.setName(t[0]);
			b.setLength(Double.parseDouble(t[1]) * IN);
			b.setOuterRadius(2.01 * IN);
			b.setThickness(0.002);
			stage.addChild(b);
			Parachute p = new Parachute();
			p.setName(t[0].startsWith("Upper") ? "Main" : "Drogue");
			p.setDiameter((t[0].startsWith("Upper") ? 60 : 18) * IN);
			p.setLength(4 * IN);
			b.addChild(p);
		}
		return d;
	}

	static Avionics.Spec spec(double band) {
		return new Avionics.Spec(12 * IN, band, Avionics.defaultAltimeters(), new Avionics.Item("9 V battery", 0.046, 2 * IN),
				new Avionics.Item("Screw switch", 0.01, 1 * IN), "nose", new Avionics.Item("GPS tracker", 0.03, 2.5 * IN),
				new Avionics.Item("Tracker battery", 0.02, 2 * IN), 0.012, 0.12, 0.25 * IN, "Fiberglass", true);
	}

	static List<MassComponent> typed(RocketComponent root, MassComponentType t) {
		List<MassComponent> out = new ArrayList<>();
		for (RocketComponent c : root) {
			if (c instanceof MassComponent m && m.getMassComponentType() == t) {
				out.add(m);
			}
		}
		return out;
	}

	@Test
	void buildsTheBayByTheRules() {
		Designs.Design d = rocket();
		var rocket = d.doc.getRocket();
		double length0 = rocket.getSelectedConfiguration().getLength();
		double mass0 = Analysis.stability(rocket.getSelectedConfiguration(), 0.3).launchMass();
		BodyTube upper = Components.find(rocket, "Upper airframe", BodyTube.class, "tube");
		BodyTube lower = Components.find(rocket, "Lower airframe", BodyTube.class, "tube");
		Map<String, Object> out = Avionics.add(d, upper, lower, spec(1 * IN));

		assertEquals(2, typed(rocket, MassComponentType.ALTIMETER).size());
		assertEquals(3, typed(rocket, MassComponentType.BATTERY).size(), "one per altimeter + the tracker's");
		assertEquals(4, typed(rocket, MassComponentType.DEPLOYMENTCHARGE).size(), "primary + backup for each event");
		List<MassComponent> tracker = typed(rocket, MassComponentType.TRACKER);
		assertEquals(1, tracker.size());
		assertTrue(tracker.get(0).getParent() instanceof NoseCone, "tracker in the RF-transparent nose");
		long switches = rocket.getChild(0).getChildCount() > 0 ? countNamed(rocket, "switch") : 0;
		assertEquals(2, switches, "one physical switch per altimeter");

		// The switch band makes the rocket exactly that much longer; the bay adds mass.
		var fc = rocket.getSelectedConfiguration();
		Analysis.settle(fc);
		assertEquals(length0 + 1 * IN, fc.getLength(), 1e-6);
		assertTrue(Analysis.stability(fc, 0.3).launchMass() > mass0 + 0.4, "bay mass is in the model");

		// Chutes are packed against the bay: the main ends where the bay's forward charges begin, the drogue starts aft of it.
		TubeCoupler coupler = null;
		for (RocketComponent c : rocket) {
			if (c instanceof TubeCoupler t) {
				coupler = t;
			}
		}
		double bayTop = coupler.toAbsolute(Coordinate.NUL)[0].x, bayBottom = bayTop + coupler.getLength();
		Parachute main = Components.find(rocket, "Main", Parachute.class, "chute");
		Parachute drogue = Components.find(rocket, "Drogue", Parachute.class, "chute");
		double mainAft = main.toAbsolute(Coordinate.NUL)[0].x + main.getLength();
		double drogueFwd = drogue.toAbsolute(Coordinate.NUL)[0].x;
		assertTrue(mainAft <= bayTop && bayTop - mainAft < 1.5 * IN, "main against the forward bulkhead: " + (bayTop - mainAft));
		assertTrue(drogueFwd >= bayBottom && drogueFwd - bayBottom < 1.5 * IN, "drogue against the aft bulkhead");
		assertTrue(out.get("staticPorts").toString().contains("4 ports"), out.toString());
	}

	static long countNamed(RocketComponent root, String part) {
		long n = 0;
		for (RocketComponent c : root) {
			if (c instanceof MassComponent && c.getName().toLowerCase().contains(part)) {
				n++;
			}
		}
		return n;
	}

	@Test
	void theRuleCheckSeesTheElectronics() {
		Designs.Design d = rocket();
		var rocket = d.doc.getRocket();
		var fc = rocket.getSelectedConfiguration();
		Requirements.Report before = new Requirements.Report();
		Requirements.electronics(before, fc);
		assertEquals("INFO", before.items.get(0).get("status"), "nothing modelled yet");
		Avionics.add(d, Components.find(rocket, "Upper airframe", BodyTube.class, "tube"),
				Components.find(rocket, "Lower airframe", BodyTube.class, "tube"), spec(1 * IN));
		Requirements.Report after = new Requirements.Report();
		Requirements.electronics(after, rocket.getSelectedConfiguration());
		assertTrue(after.items.stream().allMatch(i -> "PASS".equals(i.get("status"))), after.items.toString());
		// Take a battery away: one device without its own supply.
		MassComponent b = typed(rocket, MassComponentType.BATTERY).get(0);
		b.getParent().removeChild(b);
		Requirements.Report short1 = new Requirements.Report();
		Requirements.electronics(short1, rocket.getSelectedConfiguration());
		assertTrue(short1.items.stream().anyMatch(i -> "WARN".equals(i.get("status"))
				&& i.get("item").toString().contains("power supply")), short1.items.toString());
	}

	@Test
	void warnsWhenTheDrogueBayRunsIntoTheMotorMount() {
		Designs.Design d = rocket();
		var rocket = d.doc.getRocket();
		BodyTube lower = Components.find(rocket, "Lower airframe", BodyTube.class, "tube");
		var mount = new InnerTube();
		mount.setName("Motor mount");
		mount.setLength(34 * IN); // leaves 6 in above it: less than the coupler, charges and drogue need
		mount.setOuterRadius(1.5 * IN);
		lower.addChild(mount);
		mount.setAxialMethod(AxialMethod.BOTTOM);
		mount.setAxialOffset(0);
		Map<String, Object> out = Avionics.add(d, Components.find(rocket, "Upper airframe", BodyTube.class, "tube"), lower, spec(1 * IN));
		String packed = String.valueOf(out.get("recoveryPacked"));
		assertTrue(packed.contains("WARNING: not enough room in Lower airframe") && packed.contains("motor mount"), packed);
		// The main has plenty of room in the 24 in upper airframe.
		assertTrue(!packed.contains("room in Upper airframe"), packed);
	}

	@Test
	void refusesTubesItCannotJoin() {
		Designs.Design d = rocket();
		var rocket = d.doc.getRocket();
		BodyTube upper = Components.find(rocket, "Upper airframe", BodyTube.class, "tube");
		BodyTube lower = Components.find(rocket, "Lower airframe", BodyTube.class, "tube");
		assertThrows(ToolException.class, () -> Avionics.add(d, lower, upper, spec(1 * IN)), "wrong order");
		lower.setOuterRadius(1.5 * IN);
		assertThrows(ToolException.class, () -> Avionics.add(d, upper, lower, spec(1 * IN)), "different diameters");
	}
}
