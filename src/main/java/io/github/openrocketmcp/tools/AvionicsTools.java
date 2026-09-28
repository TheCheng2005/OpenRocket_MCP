package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Avionics;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.units.Dim;

/** Electronics: a standard avionics bay laid out by the rules. */
public final class AvionicsTools {
	private AvionicsTools() {
	}

	static final double IN = Avionics.IN;

	public static void register(McpServer s, Context ctx) {
		JsonObject item = Schema.object().str("name", "Part name, e.g. \"Missile Works RRC3\".", true)
				.qty("mass", "Mass, e.g. \"17 g\".", false).qty("length", "Length along the rocket, e.g. \"3.9 in\".", false).build();
		s.tool(new ToolDef("add_avionics_bay", "Add a dual-deploy avionics bay",
				"Build the electronics bay between two airframe tubes, laid out the way Launch Canada asks: a coupler (with an "
						+ "optional switch band) closed by two bulkheads; independent altimeter circuits, each with its own battery and "
						+ "physical switch; primary and backup ejection charges on each bulkhead (drogue aft, main forward) with "
						+ "U-bolts; the GPS tracker on its own battery in an RF-transparent nose (or in the bay); and, by default, "
						+ "the parachutes and shock cords packed against the bay. All parts are typed OpenRocket components "
						+ "(altimeter, battery, tracker, deployment charge), so mass, CG, stability and draw_rocket include them. "
						+ "Returns the layout, bay mass and static port sizes. Pass your real parts (name, mass, length) for "
						+ "accurate masses.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("upperTube", "Airframe tube forward of the bay (holds the main). Default: when the stage has exactly two "
								+ "body tubes, the first.", false)
						.str("lowerTube", "Airframe tube aft of the bay (holds the drogue). Default: the tube after upperTube.", false)
						.qty("couplerLength", "Coupler length (default 3 calibers, rounded to the inch).", false)
						.qty("switchBand", "Switch band length between the tubes (default 1 in; 0 for none).", false)
						.array("altimeters", "Altimeters, one circuit each (default a primary and a backup; use two different "
								+ "models).", item, false)
						.obj("battery", "Battery used for each altimeter (default 9 V, 46 g, 2 in).", false)
						.obj("switch", "Physical switch for each altimeter (default screw switch, 10 g).", false)
						.enumStr("tracker", "Where the GPS tracker goes (default nose).", false, "nose", "bay", "none")
						.obj("trackerPart", "The tracker (default GPS tracker, 30 g, 2.5 in).", false)
						.obj("trackerBattery", "The tracker's battery (default 1S LiPo, 20 g, 2 in).", false)
						.qty("chargeMass", "Each charge well with its black powder (default 12 g).", false)
						.qty("sledMass", "Sled, threaded rods and nuts (default 120 g).", false)
						.qty("bulkheadThickness", "Bulkhead thickness (default 1/4 in).", false)
						.str("bulkheadMaterial", "Bulkhead material (default Fiberglass; e.g. Birch for plywood).", false)
						.bool("packRecovery", "Move the parachutes and shock cords against the bay (default true).", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					var rocket = d.doc.getRocket();
					BodyTube upper, lower;
					if (a.has("upperTube")) {
						upper = Components.find(rocket, a.str("upperTube"), BodyTube.class, "body tube");
					} else {
						List<BodyTube> tubes = new ArrayList<>();
						for (RocketComponent c : rocket.getChild(0).getChildren()) {
							if (c instanceof BodyTube b) {
								tubes.add(b);
							}
						}
						if (tubes.size() != 2) {
							throw new ToolException("Say which tubes the bay goes between (upperTube, lowerTube): the stage has "
									+ tubes.size() + " body tubes.");
						}
						upper = tubes.get(0);
					}
					if (a.has("lowerTube")) {
						lower = Components.find(rocket, a.str("lowerTube"), BodyTube.class, "body tube");
					} else {
						int i = upper.getParent().getChildPosition(upper);
						RocketComponent next = i + 1 < upper.getParent().getChildCount() ? upper.getParent().getChild(i + 1) : null;
						if (!(next instanceof BodyTube b)) {
							throw new ToolException("There is no body tube right after " + upper.getName() + "; pass lowerTube.");
						}
						lower = b;
					}
					double cal = upper.getOuterRadius() * 2;
					double lc = a.qty("couplerLength", Dim.LENGTH, Math.round(3 * cal / IN) * IN);
					List<Avionics.Item> alts = a.has("altimeters") ? items(a.objList("altimeters"), 0.025, 3.5 * IN)
							: Avionics.defaultAltimeters();
					if (alts.isEmpty()) {
						throw new ToolException("Give at least one altimeter.");
					}
					Avionics.Spec spec = new Avionics.Spec(lc, a.qty("switchBand", Dim.LENGTH, 1 * IN), alts,
							item(a.has("battery") ? a.obj("battery") : null, "9 V battery", 0.046, 2 * IN),
							item(a.has("switch") ? a.obj("switch") : null, "Screw switch", 0.010, 1 * IN), a.str("tracker", "nose"),
							item(a.has("trackerPart") ? a.obj("trackerPart") : null, "GPS tracker", 0.030, 2.5 * IN),
							item(a.has("trackerBattery") ? a.obj("trackerBattery") : null, "Tracker battery (1S LiPo)", 0.020, 2 * IN),
							a.qty("chargeMass", Dim.MASS, 0.012), a.qty("sledMass", Dim.MASS, 0.120),
							a.qty("bulkheadThickness", Dim.LENGTH, 0.25 * IN), a.str("bulkheadMaterial", "Fiberglass"),
							a.bool("packRecovery", true));
					return Avionics.add(d, upper, lower, spec);
				}));
	}

	static List<Avionics.Item> items(List<Args> list, double mass, double length) {
		List<Avionics.Item> out = new ArrayList<>();
		for (Args x : list) {
			out.add(item(x, x.str("name"), mass, length));
		}
		return out;
	}

	static Avionics.Item item(Args x, String name, double mass, double length) {
		if (x == null) {
			return new Avionics.Item(name, mass, length);
		}
		return new Avionics.Item(x.str("name", name), x.qty("mass", Dim.MASS, mass), x.qty("length", Dim.LENGTH, length));
	}
}
