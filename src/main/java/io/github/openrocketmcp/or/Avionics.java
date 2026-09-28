package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonPrimitive;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Bulkhead;
import info.openrocket.core.rocketcomponent.ExternalComponent;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.MassComponent.MassComponentType;
import info.openrocket.core.rocketcomponent.MassObject;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * A standard dual-deploy avionics bay between two airframe tubes, laid out the way Launch Canada asks: a coupler
 * (optionally with a switch band) closed by two bulkheads; independent altimeters, each with its own battery and physical
 * switch (one altimeter per circuit); primary and backup ejection charges on each bulkhead, drogue charges facing the aft
 * bay and main charges facing the forward bay; the GPS tracker on its own battery in the nose when the nose is
 * RF-transparent. Everything is a typed OpenRocket part, so masses, CG and the drawing are right.
 */
public final class Avionics {
	private Avionics() {
	}

	public static final double IN = 0.0254;

	/** An electronics item: name, mass (kg), length (m). */
	public record Item(String name, double mass, double length) {
	}

	public record Spec(double couplerLength, double switchBand, List<Item> altimeters, Item battery, Item sw, String tracker,
			Item trackerItem, Item trackerBattery, double chargeMass, double sledMass, double bulkheadThickness,
			String bulkheadMaterial, boolean packRecovery) {
	}

	public static List<Item> defaultAltimeters() {
		return List.of(new Item("Primary altimeter", 0.025, 3.5 * IN), new Item("Backup altimeter", 0.025, 3.5 * IN));
	}

	/** Materials that block GPS / radio signals. */
	static boolean rfOpaque(String material) {
		String m = material.toLowerCase(Locale.ROOT);
		return m.contains("carbon") || m.contains("alumin") || m.contains("steel") || m.contains("titanium") || m.contains("brass")
				|| m.contains("copper");
	}

	static MassComponent mass(String name, MassComponentType type, double kg, double length, double radius) {
		MassComponent m = new MassComponent();
		m.setName(name);
		m.setMassComponentType(type);
		m.setComponentMass(kg);
		m.setLength(length);
		m.setRadius(radius);
		return m;
	}

	static void at(RocketComponent c, AxialMethod method, double offset) {
		c.setAxialMethod(method);
		c.setAxialOffset(offset);
	}

	static void radial(MassObject m, double r, double angle) {
		m.setRadialPosition(r);
		m.setRadialDirection(angle);
	}

	public static Map<String, Object> add(Designs.Design d, BodyTube upper, BodyTube lower, Spec s) {
		if (upper.getParent() != lower.getParent() || upper == lower) {
			throw new ToolException("The upper and lower airframe must be two different body tubes of the same stage.");
		}
		RocketComponent stage = upper.getParent();
		if (stage.getChildPosition(lower) != stage.getChildPosition(upper) + 1) {
			throw new ToolException("The lower airframe must come right after the upper airframe (the bay goes between them).");
		}
		double ri = upper.getInnerRadius();
		if (Math.abs(ri - lower.getInnerRadius()) > 0.0005) {
			throw new ToolException("The two airframe tubes have different inside diameters; a single coupler cannot join them.");
		}
		double wall = Math.max(0.0015, upper.getOuterRadius() - ri);
		String material = ((ExternalComponent) upper).getMaterial().getName();
		double lc = s.couplerLength();
		if (lc < s.switchBand() + 2 * IN) {
			throw new ToolException("The coupler must be at least 2 in longer than the switch band (1 in each side into the airframe).");
		}
		List<String> notes = new ArrayList<>();

		// Switch band and coupler.
		RocketComponent couplerParent = upper;
		if (s.switchBand() > 0) {
			BodyTube band = new BodyTube();
			band.setName("Switch band");
			band.setLength(s.switchBand());
			band.setOuterRadius(upper.getOuterRadius());
			band.setThickness(upper.getOuterRadius() - ri);
			Components.set(band, "material", new JsonPrimitive(material));
			stage.addChild(band, stage.getChildPosition(upper) + 1);
			couplerParent = band;
		}
		TubeCoupler coupler = new TubeCoupler();
		coupler.setName("Av-bay coupler");
		coupler.setOuterRadiusAutomatic(true);
		coupler.setLength(lc);
		coupler.setThickness(Math.min(wall, ri * 0.1));
		couplerParent.addChild(coupler);
		Components.set(coupler, "material", new JsonPrimitive(material));
		if (s.switchBand() > 0) {
			at(coupler, AxialMethod.MIDDLE, 0);
		} else {
			at(coupler, AxialMethod.BOTTOM, lc / 2);
		}
		double rc = coupler.getInnerRadius();

		// Bulkheads.
		double t = s.bulkheadThickness();
		for (boolean top : new boolean[] { true, false }) {
			Bulkhead b = new Bulkhead();
			b.setName(top ? "Av-bay bulkhead (forward, main side)" : "Av-bay bulkhead (aft, drogue side)");
			b.setOuterRadiusAutomatic(true);
			b.setLength(t);
			coupler.addChild(b);
			Components.set(b, "material", new JsonPrimitive(s.bulkheadMaterial()));
			at(b, top ? AxialMethod.TOP : AxialMethod.BOTTOM, 0);
		}

		// Sled, altimeters, batteries and switches: one independent circuit per altimeter.
		double usable = lc - 2 * t;
		coupler.addChild(pos(mass("Sled, threaded rods and nuts", MassComponentType.RECOVERYHARDWARE, s.sledMass(), usable * 0.96,
				rc * 0.85), AxialMethod.MIDDLE, 0));
		List<Item> alts = s.altimeters();
		int n = alts.size();
		double slot = (usable - 0.02) / n; // each circuit gets an equal share of the sled
		for (int i = 0; i < n; i++) {
			Item a = alts.get(i);
			double start = t + 0.01 + i * slot;
			double battLen = Math.min(s.battery().length(), slot * 0.35);
			double altLen = Math.min(a.length(), slot * 0.6);
			boolean flip = i % 2 == 1; // mirror the second circuit so the batteries sit at the bay ends
			double battX = flip ? start + slot - battLen : start;
			double altX = flip ? start : start + battLen + slot * 0.03;
			coupler.addChild(pos(mass(a.name(), MassComponentType.ALTIMETER, a.mass(), altLen, rc * 0.35), AxialMethod.TOP, altX));
			coupler.addChild(pos(mass(s.battery().name() + " (" + a.name().toLowerCase(Locale.ROOT) + ")", MassComponentType.BATTERY,
					s.battery().mass(), battLen, rc * 0.3), AxialMethod.TOP, battX));
			MassComponent sw = mass(s.sw().name() + " (" + a.name().toLowerCase(Locale.ROOT) + ")", MassComponentType.MASSCOMPONENT,
					s.sw().mass(), s.sw().length(), rc * 0.12);
			radial(sw, rc * 0.8, i % 2 == 0 ? 0 : Math.PI);
			coupler.addChild(pos(sw, AxialMethod.MIDDLE, 0)); // switches reach the outside through the switch band
		}

		// Charges and U-bolts on the outer faces of the bulkheads.
		double cl = 1.0 * IN;
		String[][] events = { { "Main", "TOP" }, { "Drogue", "BOTTOM" } };
		for (String[] e : events) {
			boolean top = e[1].equals("TOP");
			for (int k = 0; k < 2; k++) {
				MassComponent ch = mass(e[0] + " charge (" + (k == 0 ? "primary" : "backup") + ")", MassComponentType.DEPLOYMENTCHARGE,
						s.chargeMass(), cl, 0.35 * IN);
				radial(ch, rc * 0.5, k == 0 ? 0 : Math.PI);
				coupler.addChild(pos(ch, top ? AxialMethod.TOP : AxialMethod.BOTTOM, top ? -cl : cl));
			}
			coupler.addChild(pos(mass("U-bolt (" + e[0].toLowerCase(Locale.ROOT) + " harness)", MassComponentType.RECOVERYHARDWARE, 0.03,
					0.8 * IN, 0.4 * IN), top ? AxialMethod.TOP : AxialMethod.BOTTOM, top ? -0.8 * IN : 0.8 * IN));
		}
		double faceClear = cl + 0.1 * IN;

		// Tracker.
		String where = s.tracker();
		RocketComponent nose = null;
		for (RocketComponent c : stage.getParent().getChild(0).getChildren()) {
			if (c instanceof NoseCone) {
				nose = c;
			}
		}
		if (!"none".equals(where)) {
			if ("nose".equals(where) && nose == null) {
				where = "bay";
				notes.add("No nose cone found: the tracker goes in the av-bay.");
			}
			if ("nose".equals(where) && rfOpaque(((ExternalComponent) nose).getMaterial().getName())) {
				notes.add("The nose is " + ((ExternalComponent) nose).getMaterial().getName() + ", which blocks GPS and radio: use an "
						+ "RF-transparent (fiberglass) nose or move the antenna outside.");
			}
			if (!"nose".equals(where) && rfOpaque(material)) {
				notes.add("The airframe is " + material + ", which blocks GPS and radio: the tracker needs an RF-transparent "
						+ "section or an external antenna.");
			}
			MassComponent tr = mass(s.trackerItem().name(), MassComponentType.TRACKER, s.trackerItem().mass(), s.trackerItem().length(),
					rc * 0.35);
			MassComponent tb = mass(s.trackerBattery().name(), MassComponentType.BATTERY, s.trackerBattery().mass(),
					s.trackerBattery().length(), rc * 0.3);
			if ("nose".equals(where)) {
				NoseCone nc = (NoseCone) nose;
				double shoulder = nc.getAftShoulderLength();
				double need = tr.getLength() + tb.getLength() + 0.005;
				// In the shoulder if it fits (full diameter), otherwise just forward of it, inside the cone.
				double end = shoulder >= need ? need : -0.01;
				nc.addChild(pos(tr, AxialMethod.BOTTOM, end - tb.getLength() - 0.005));
				nc.addChild(pos(tb, AxialMethod.BOTTOM, end));
				double rAt = nc.getRadius(Math.max(0, nc.getLength() + end - need));
				if (shoulder < need) {
					tr.setRadius(Math.min(tr.getRadius(), rAt * 0.7));
					tb.setRadius(Math.min(tb.getRadius(), rAt * 0.6));
				}
			} else {
				radial(tr, rc * 0.55, Math.PI / 2);
				radial(tb, rc * 0.55, -Math.PI / 2);
				coupler.addChild(pos(tr, AxialMethod.MIDDLE, 0));
				coupler.addChild(pos(tb, AxialMethod.MIDDLE, tr.getLength()));
			}
		}

		// Recovery: pack chutes and shock cords against the bay's bulkheads (main forward of it, drogue aft of it).
		List<String> packed = new ArrayList<>();
		if (s.packRecovery()) {
			double protrudeUp = s.switchBand() > 0 ? (lc - s.switchBand()) / 2 : lc / 2;
			double protrudeDown = protrudeUp;
			packed.addAll(pack(upper, true, protrudeUp + faceClear));
			packed.addAll(pack(lower, false, protrudeDown + faceClear));
		}
		d.doc.setSaved(false);
		for (var fc : d.doc.getRocket().getFlightConfigurations()) {
			Analysis.settle(fc);
		}

		// Report: layout from the nose tip, bay mass, static ports.
		List<Map<String, Object>> layout = new ArrayList<>();
		double bayMass = 0;
		List<RocketComponent> inBay = new ArrayList<>();
		for (RocketComponent c : coupler) {
			inBay.add(c);
		}
		inBay.sort((a1, b1) -> Double.compare(a1.toAbsolute(Coordinate.NUL)[0].x, b1.toAbsolute(Coordinate.NUL)[0].x));
		for (RocketComponent c : inBay) {
			double x = c.toAbsolute(Coordinate.NUL)[0].x;
			if (c != coupler && !(c instanceof Bulkhead)) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("part", c.getName());
				m.put("fromNoseTip", Units.fmt(x, Dim.LENGTH));
				m.put("mass", Units.fmt(c.getMass(), Dim.MASS));
				layout.add(m);
			}
			bayMass += c.getMass();
		}
		double volIn3 = Math.PI * rc * rc * usable / Math.pow(IN, 3);
		// Rule of thumb from altimeter manuals: one 1/4 in port per 100 in3 of bay volume; split over 4 ports around the
		// band (concentric sampling cancels crosswind pressure).
		double areaIn2 = volIn3 / 100 * Math.PI * 0.125 * 0.125;
		double d4 = 2 * Math.sqrt(areaIn2 / 4 / Math.PI);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("added", "Av-bay: " + (s.switchBand() > 0 ? Units.fmt(s.switchBand(), Dim.LENGTH) + " switch band, " : "")
				+ Units.fmt(lc, Dim.LENGTH) + " coupler between " + upper.getName() + " and " + lower.getName());
		out.put("electronics", n + " independent altimeter circuits (each: altimeter + own battery + own physical switch), "
				+ (where.equals("none") ? "no tracker" : "GPS tracker on its own battery in the " + where));
		out.put("charges", "main primary + backup on the forward bulkhead, drogue primary + backup on the aft bulkhead");
		out.put("bayMass", Units.fmt(bayMass, Dim.MASS));
		out.put("layout", layout);
		out.put("staticPorts", String.format(Locale.ROOT, "bay volume %.0f in3: 4 ports of %.3f in (%.1f mm) evenly around the "
				+ "switch band (same area as %.0f x 1/4 in); follow your altimeter's manual, and keep ports clear of the "
				+ "switches and away from shoulders", volIn3, d4, d4 * 25.4, Math.max(1, volIn3 / 100)));
		if (!packed.isEmpty()) {
			out.put("recoveryPacked", packed);
		}
		List<String> rules = new ArrayList<>(List.of(
				"One altimeter per circuit, each with its own battery and physical switch (Launch Canada 2027 electronics edicts)",
				"The tracker has its own battery", "Above Mach 0.7 the bay must sample through concentric ports (see staticPorts)",
				"Wrap and pad lithium cells; never zip-tie them to a plank"));
		out.put("rulesFollowed", rules);
		notes.add("Masses and sizes are typical defaults unless you gave your parts: weigh the finished bay and set its real mass.");
		notes.add("Use two different altimeter models so one firmware or hardware fault cannot take out both.");
		out.put("notes", notes);
		return out;
	}

	private static RocketComponent pos(RocketComponent c, AxialMethod m, double off) {
		c.setAxialMethod(m);
		c.setAxialOffset(off);
		return c;
	}

	/**
	 * Moves the tube's shock cords and recovery devices against the bay: in the upper tube they stack upward from its
	 * bottom, in the lower tube downward from its top. Returns what was moved.
	 */
	static List<String> pack(BodyTube tube, boolean upper, double start) {
		List<MassObject> items = new ArrayList<>();
		for (RocketComponent c : tube.getChildren()) {
			if (c instanceof ShockCord sc) {
				items.add(0, sc); // cord nearest the bulkhead, chute beyond it
			} else if (c instanceof RecoveryDevice rd) {
				items.add(rd);
			}
		}
		List<String> moved = new ArrayList<>();
		double at = start;
		for (MassObject m : items) {
			if (upper) {
				pos(m, AxialMethod.BOTTOM, -at);
			} else {
				pos(m, AxialMethod.TOP, at);
			}
			at += m.getLength() + 0.005;
			moved.add(m.getName() + " packed " + (upper ? "just forward of" : "just aft of") + " the av-bay");
		}
		double room = room(tube, upper);
		if (!items.isEmpty() && at > room) {
			moved.add("WARNING: not enough room in " + tube.getName() + ": the charges, shock cord and parachute need "
					+ Units.fmt(at, Dim.LENGTH) + " from the av-bay but only " + Units.fmt(room, Dim.LENGTH) + " is free before "
					+ (upper ? "the nose shoulder" : "the motor mount") + ". Lengthen the airframe, shorten the coupler, or use a "
					+ "smaller-packing parachute.");
		}
		return moved;
	}

	/**
	 * Free length from the av-bay end of the tube to the first obstruction: for the lower tube the forward end of the
	 * motor mount, centering rings, bulkheads or the motor; for the upper tube the nose (or transition) shoulder reaching
	 * into it.
	 */
	static double room(BodyTube tube, boolean upper) {
		double top = tube.toAbsolute(Coordinate.NUL)[0].x, bottom = top + tube.getLength();
		if (upper) {
			double limit = top;
			RocketComponent parent = tube.getParent();
			int i = parent.getChildPosition(tube);
			if (i > 0 && parent.getChild(i - 1) instanceof info.openrocket.core.rocketcomponent.Transition t) {
				limit = top + t.getAftShoulderLength();
			}
			return bottom - limit;
		}
		double first = bottom;
		for (RocketComponent c : tube.getChildren()) {
			boolean blocks = c instanceof info.openrocket.core.rocketcomponent.InnerTube
					|| c instanceof info.openrocket.core.rocketcomponent.CenteringRing
					|| c instanceof info.openrocket.core.rocketcomponent.Bulkhead
					|| c instanceof info.openrocket.core.rocketcomponent.EngineBlock;
			if (blocks) {
				first = Math.min(first, c.toAbsolute(Coordinate.NUL)[0].x);
			}
			if (c instanceof info.openrocket.core.rocketcomponent.MotorMount mm && mm.isMotorMount()) {
				var conf = mm.getMotorConfig(tube.getRocket().getSelectedConfiguration().getId());
				if (conf != null && conf.getMotor() != null) { // a motor longer than its mount reaches further forward
					double aft = c.toAbsolute(Coordinate.NUL)[0].x + c.getLength() + mm.getMotorOverhang();
					first = Math.min(first, aft - conf.getMotor().getLength());
				}
			}
		}
		return first - top;
	}
}
