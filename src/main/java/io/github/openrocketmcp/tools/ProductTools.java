package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.ElectronicsSummary;
import io.github.openrocketmcp.or.Motors;
import io.github.openrocketmcp.or.ParachuteSizes;
import io.github.openrocketmcp.or.Presets;
import io.github.openrocketmcp.or.Products;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Web;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** add_from_link: a product page (link or pasted text) into the design. */
public final class ProductTools {
	private ProductTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("add_from_link", "Add a product from its web page",
				"Give the link to a product page (or paste the page's text) and the part goes into the design: a parachute "
						+ "(diameter, Cd, weight and packed size, gaps filled from a matching catalog parachute), a motor (its "
						+ "designation, or a ThrustCurve.org link; motors newer than OpenRocket's database are downloaded from "
						+ "ThrustCurve.org), a catalog part by its part number (tubes, nose cones, couplers, rings), or an item to "
						+ "carry (altimeter, tracker, battery: its weight and size, into the av-bay by default). Every value says "
						+ "where it came from (page, catalog, given, assumed). The design is re-flown and the effect reported; "
						+ "undo reverts it. Values given here (cd, diameter, mass) override the page.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("url", "Link to the product page.", false)
						.str("pageText", "The page's text or HTML, when the server cannot reach the shop (select all, copy, paste).", false)
						.str("component", "The part this replaces or updates (a parachute, motor mount, tube...).", false)
						.str("parent", "Where to add it as a new part (e.g. the av-bay coupler or a body tube).", false)
						.enumStr("kind", "What it is, when the page is unclear.", false, "parachute", "motor", "part", "item")
						.num("cd", "Parachute Cd, overriding the page (with the reference area it was measured on).", false)
						.qty("diameter", "Parachute diameter, overriding the page.", false)
						.qty("mass", "Weight, overriding the page.", false)
						.bool("apply", "Change the design (default true); false only reports what the page gives.", false).build(),
				false, a -> addFromLink(ctx, a)));
	}

	static Map<String, Object> addFromLink(Context ctx, Args a) throws Exception {
		if (!a.has("url") && !a.has("pageText")) {
			throw new ToolException("Give url (the product page) or pageText (the page's text pasted).");
		}
		String url = a.str("url", null);
		String html = a.has("pageText") ? a.str("pageText") : Web.get(url);
		Products.Kind forced = a.has("kind") ? Products.Kind.valueOf(a.str("kind").toUpperCase(Locale.ROOT)) : null;
		Products.Product p = Products.identify(Products.read(url, html), forced);
		Map<String, Products.Value> v = new LinkedHashMap<>(p.values());
		if (a.has("cd")) {
			v.put("cd", new Products.Value(a.num("cd"), Dim.DIMENSIONLESS, "given"));
		}
		if (a.has("diameter")) {
			v.put("diameter", new Products.Value(a.positive("diameter", Dim.LENGTH), Dim.LENGTH, "given"));
		}
		if (a.has("mass")) {
			v.put("mass", new Products.Value(a.positive("mass", Dim.MASS), Dim.MASS, "given"));
		}

		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> prod = new LinkedHashMap<>();
		prod.put("name", p.page().name());
		if (p.page().brand() != null) {
			prod.put("brand", p.page().brand());
		}
		prod.put("kind", p.kind().name().toLowerCase(Locale.ROOT));
		if (p.page().sku() != null) {
			prod.put("partNumber", p.page().sku());
		}
		if (url != null) {
			prod.put("link", url);
		}
		out.put("product", prod);
		Map<String, Object> found = new LinkedHashMap<>();
		v.forEach((k, x) -> found.put(k, x.dim() == Dim.DIMENSIONLESS ? Units.num(x.si()) + " (" + x.source() + ")" : x.show()));
		if (p.motor() != null) {
			found.put("motor", (p.motorMaker() == null ? "" : p.motorMaker() + " ") + p.motor());
		}
		if (p.preset() != null) {
			found.put("catalogMatch", Presets.name(p.preset()) + (p.preset().has(ComponentPreset.DESCRIPTION)
					? " (" + p.preset().get(ComponentPreset.DESCRIPTION) + ")" : ""));
		}
		out.put("fromThePage", found);
		List<String> notes = new ArrayList<>(p.notes());
		if (p.kind() == Products.Kind.UNKNOWN) {
			throw new ToolException("Could not tell what '" + p.page().name() + "' is from the page (no parachute size, motor "
					+ "designation, catalog part number or weight found). Give kind and the values (cd, diameter, mass), or "
					+ "add it with add_component.");
		}
		if (!a.bool("apply", true)) {
			notes.add("Nothing changed (apply=false).");
			out.put("notes", notes);
			return out;
		}
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Rocket rocket = d.doc.getRocket();
		Map<String, Object> applied = switch (p.kind()) {
			case PARACHUTE -> parachute(ctx, a, d, rocket, p, v, notes);
			case MOTOR -> motor(ctx, a, d, rocket, p, notes);
			case PART -> part(a, rocket, p);
			case ITEM -> item(a, rocket, p, v, notes);
			default -> throw new IllegalStateException();
		};
		d.doc.setSaved(false);
		out.put("applied", applied);
		notes.add("Check the values against the vendor's sheet: shops word their pages differently. undo reverts this.");
		out.put("notes", notes);
		return out;
	}

	private static Map<String, Object> parachute(Context ctx, Args a, Designs.Design d, Rocket rocket, Products.Product p,
			Map<String, Products.Value> v, List<String> notes) {
		if (!v.containsKey("diameter")) {
			throw new ToolException("The page gives no parachute diameter: pass diameter (e.g. \"84 in\").");
		}
		Parachute chute;
		boolean added = false;
		if (a.has("component")) {
			chute = Components.find(rocket, a.str("component"), Parachute.class, "parachute");
		} else if (a.has("parent")) {
			chute = new Parachute();
			Components.find(rocket, a.str("parent")).addChild(chute);
			added = true;
		} else {
			List<Parachute> all = new ArrayList<>();
			for (RocketComponent c : rocket) {
				if (c instanceof Parachute pc) {
					all.add(pc);
				}
			}
			if (all.size() != 1) {
				throw new ToolException(all.isEmpty() ? "The design has no parachute: give parent (where it goes)."
						: "Say which parachute this replaces (component: " + all.stream().map(Parachute::getName).toList()
								+ ") or where a new one goes (parent).");
			}
			chute = all.get(0);
		}
		double before = Double.NaN;
		if (!added) {
			before = steadyRate(ctx, a, chute);
		}
		String old = chute.getName();
		chute.setName(shortName(p.page().name()));
		chute.setDiameter(v.get("diameter").si());
		chute.setCD(v.get("cd").si());
		if (v.containsKey("mass")) {
			chute.setMassOverridden(true);
			chute.setOverrideMass(v.get("mass").si());
		}
		if (v.containsKey("packedDiameter")) {
			chute.setRadius(v.get("packedDiameter").si() / 2);
			chute.setLength(v.get("packedLength").si());
		} else {
			notes.add("No packed size on the page: the packed length and diameter in the design are unchanged.");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put(added ? "added" : "replaced", added ? chute.getName() + " in " + chute.getParent().getName() : old + " -> " + chute.getName());
		double after = steadyRate(ctx, a, chute);
		out.put("descentRate", (Double.isNaN(before) ? "" : Units.fmt(before, Dim.VELOCITY) + " -> ") + Units.fmt(after, Dim.VELOCITY)
				+ " (simulated)");
		if (added) {
			notes.add("A new parachute opens at apogee by default: set_deployment for when it should open.");
		}
		return out;
	}

	private static double steadyRate(Context ctx, Args a, Parachute chute) {
		try {
			Simulation sim = SimTools.runSelected(ctx, new Args(designOnly(a)));
			return ParachuteSizes.fromFlight(sim, chute.getID().toString(), 1).simulatedRate();
		} catch (ToolException e) {
			return Double.NaN; // no motor yet, or it does not open: the change still stands
		}
	}

	private static Map<String, Object> motor(Context ctx, Args a, Designs.Design d, Rocket rocket, Products.Product p, List<String> notes)
			throws Exception {
		if (p.motor() == null) {
			throw new ToolException("No motor designation on the page: use set_motor with the designation.");
		}
		ThrustCurveMotor m = null;
		// Shops add the delay to the name ("J350W-L", "K1100T-14A"); the database lists the motor without it.
		for (String des : List.of(p.motor(), p.motor().replaceFirst("-(\\d{1,2}[A-Z]?|[A-Z])$", ""))) {
			try {
				m = Motors.find(des, p.motorMaker());
				break;
			} catch (ToolException notHere) {
				// next form, then ThrustCurve.org
			}
		}
		if (m == null) {
			try {
				m = Motors.fromThrustCurve(p.motorMaker(), p.motor().replaceFirst("-(\\d{1,2}[A-Z]?|[A-Z])$", ""), ctx.path("motors"));
			} catch (ToolException e) {
				throw new ToolException(p.motor() + " is not in OpenRocket's motor database and could not be downloaded from "
						+ "ThrustCurve.org (" + e.getMessage().replaceAll("\\.? ?Without internet.*", "") + "). Download its .eng or .rse "
						+ "file from thrustcurve.org, then import_motor_file and set_motor.");
			}
			notes.add("Not in OpenRocket's motor database: downloaded from ThrustCurve.org into motors/.");
		}
		RocketComponent mount;
		if (a.has("component")) {
			mount = Components.find(rocket, a.str("component"));
		} else {
			List<RocketComponent> mounts = new ArrayList<>();
			for (RocketComponent c : rocket) {
				if (c instanceof MotorMount mm && mm.isMotorMount()) {
					mounts.add(c);
				}
			}
			if (mounts.size() != 1) {
				throw new ToolException("Say which motor mount takes it (component" + (mounts.isEmpty() ? "" : ": "
						+ mounts.stream().map(RocketComponent::getName).toList()) + ").");
			}
			mount = mounts.get(0);
		}
		Map<String, Object> out = MotorTools.putMotor(d, mount, m, new Args(null));
		double mountD = ((MotorMount) mount).getMotorMountDiameter();
		if (m.getDiameter() < mountD - 0.002) {
			notes.add("A " + Units.fmt(m.getDiameter(), Dim.LENGTH) + " motor in a " + Units.fmt(mountD, Dim.LENGTH)
					+ " mount needs a motor adapter; its mass is not in the design (add_component).");
		}
		try {
			Map<String, Object> flight = Sims.summarize(SimTools.runSelected(ctx, new Args(designOnly(a))));
			out.put("flight", flight.get("flight"));
			if (flight.containsKey("PROBLEMS")) {
				out.put("PROBLEMS", flight.get("PROBLEMS"));
			}
		} catch (ToolException e) {
			notes.add("Not simulated: " + e.getMessage());
		}
		return out;
	}

	private static Map<String, Object> part(Args a, Rocket rocket, Products.Product p) {
		ComponentPreset preset = p.preset();
		RocketComponent target;
		if (a.has("component")) {
			target = Components.find(rocket, a.str("component"));
		} else {
			List<RocketComponent> same = new ArrayList<>();
			for (RocketComponent c : rocket) {
				if (c.getPresetType() == preset.getType()) {
					same.add(c);
				}
			}
			if (same.size() != 1) {
				throw new ToolException("Say which part this is (component" + (same.isEmpty() ? "; the design has no "
						+ preset.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') : ": "
								+ same.stream().map(RocketComponent::getName).toList()) + ").");
			}
			target = same.get(0);
		}
		if (target.getPresetType() != preset.getType()) {
			throw new ToolException(Presets.name(preset) + " is a " + preset.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
					+ "; " + target.getName() + " is not.");
		}
		target.loadPreset(preset);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("loaded", Presets.name(preset) + " into " + target.getName());
		out.put("part", Presets.render(preset));
		return out;
	}

	private static Map<String, Object> item(Args a, Rocket rocket, Products.Product p, Map<String, Products.Value> v,
			List<String> notes) {
		if (!v.containsKey("mass")) {
			throw new ToolException("The page gives no weight for " + p.page().name() + ": pass mass (e.g. \"18 g\").");
		}
		MassComponent mc;
		String where;
		if (a.has("component")) {
			mc = Components.find(rocket, a.str("component"), MassComponent.class, "mass item");
			where = "updated " + mc.getName();
		} else {
			RocketComponent parent = a.has("parent") ? Components.find(rocket, a.str("parent")) : ElectronicsSummary.bay(rocket);
			if (parent == null) {
				throw new ToolException("Say where it goes (parent), e.g. the av-bay coupler or a body tube.");
			}
			mc = new MassComponent();
			parent.addChild(mc);
			where = "added to " + parent.getName();
			notes.add("Placed at the front of " + parent.getName() + ": move it with edit_components (axialOffset).");
		}
		mc.setName(shortName(p.page().name()));
		mc.setComponentMass(v.get("mass").si());
		if (v.containsKey("length")) {
			mc.setLength(v.get("length").si());
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("item", mc.getName() + ": " + v.get("mass").show() + ", " + where);
		return out;
	}

	private static com.google.gson.JsonObject designOnly(Args a) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		if (a.has("designId")) {
			o.addProperty("designId", a.str("designId"));
		}
		return o;
	}

	private static String shortName(String name) {
		String n = name == null ? "Product" : name.trim();
		return n.length() > 60 ? n.substring(0, 57) + "..." : n;
	}
}
