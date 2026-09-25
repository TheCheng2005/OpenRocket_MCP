package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Structures;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Fin flutter and nose ballast. */
public final class StructureTools {
	private StructureTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("fin_flutter", "Fin flutter margin along the flight",
				"Simulate and compute each fin set's flutter speed (NACA TN 4197, corrected constant per Apogee Peak of Flight "
						+ "#615) at every point of the flight from the local pressure and speed of sound, and report the worst "
						+ "margin (flutter speed / airspeed), where it occurs, and the thickness or shear modulus needed to reach the "
						+ "team's margin (structures.flutterMinMargin, default 1.5). Shear modulus comes from the fin material via "
						+ "structures.shearModulus in the team standards unless given. A screening estimate for solid plate fins.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.qty("shearModulus", "Shear modulus for all fin sets, e.g. \"2.9 GPa\" (G10) or \"26 GPa\" (6061-T6).", false)
						.str("finSet", "Only this fin set (id or name).", false)
						.qty("thickness", "What-if fin thickness (all fin sets), without editing the design.", false).build(),
				true, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					Map<String, Double> mod = new HashMap<>();
					if (a.has("shearModulus")) {
						mod.put("*", a.qty("shearModulus", Dim.PRESSURE));
					}
					String only = null;
					if (a.has("finSet")) {
						Designs.Design d = ctx.designs.get(a.str("designId", null));
						only = Components.find(d.doc.getRocket(), a.str("finSet"), FinSet.class, "fin set").getID().toString();
					}
					List<Map<String, Object>> sets = new ArrayList<>();
					for (Structures.FlutterResult r : Structures.flutter(sim, ctx.standards(), mod, a.qtyOrNaN("thickness", Dim.LENGTH))) {
						if (only == null || r.fin().getID().toString().equals(only)) {
							sets.add(Structures.render(r, ctx.standards()));
						}
					}
					if (sets.isEmpty()) {
						throw new ToolException("The active configuration has no fin sets.");
					}
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("finSets", sets);
					out.put("maxMach", Units.num(sim.getSimulatedData().getMaxMachNumber()));
					out.put("method", "Vf = a sqrt(G / (K AR^3 (taper+1) P / (2 (AR+2) (t/c)^3))), K = "
							+ Units.num(ctx.standards().q("structures.flutterConstant", Dim.DIMENSIONLESS, 2.674))
							+ " (NACA TN 4197; the 1.337 form in Peak of Flight #291 overestimates flutter speed by sqrt 2)");
					out.put("notes", List.of(
							"Solid, isotropic, cantilevered plate. Composite, sandwich and tip-to-tip fins need the laminate's "
									+ "effective shear modulus; the estimate is a screen, not a clearance.",
							"Freeform or elliptical fins use an equivalent trapezoid with the same span, root chord and area.",
							"Fins of a lower stage are checked until that stage separates."));
					return out;
				}));

		s.tool(new ToolDef("ballast", "Nose ballast for a stability target",
				"How much ballast, at a given location, brings the minimum ascent stability (simulated, rail exit to apogee) to a "
						+ "target: an analytic first guess from the static margin, refined by simulations. Reports the mass, the "
						+ "apogee and rail-exit cost, and whether the target is reachable from that location. Does not edit the "
						+ "design; add the mass with add_component (MassComponent) once agreed. Default location: the nose cone's "
						+ "shoulder region (60% of its length).",
				SimTools.simSelect(Schema.object())
						.num("targetStability", "Target minimum ascent stability in calibers. Default: the rule set's floor, "
								+ "max(minimum calibers, % of body length x L:D).", false)
						.str("component", "Component that holds the ballast (default: the nose cone).", false)
						.qty("position", "Ballast center, measured aft from the front of that component (default 60% of its "
								+ "length).", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
							ctx.standards(), false);
					FlightConfiguration fc = base.getRocket().getFlightConfiguration(base.getFlightConfigurationId());
					RocketComponent holder;
					if (a.has("component")) {
						holder = Components.find(base.getRocket(), a.str("component"));
					} else {
						holder = null;
						for (RocketComponent c : fc.getActiveComponents()) {
							if (c instanceof NoseCone) {
								holder = c;
								break;
							}
						}
						if (holder == null) {
							throw new ToolException("No nose cone found; give component.");
						}
					}
					double pos = a.qty("position", Dim.LENGTH, 0.6 * holder.getLength());
					double target = a.num("targetStability", Double.NaN);
					String basis = "given";
					if (Double.isNaN(target)) {
						double[] f = Requirements.stabilityFloor(ctx.standards(), fc);
						target = f[0] > 0 ? f[0] : 1.5;
						basis = f[0] > 0 ? "rule set floor: " + Requirements.floorText(f) : "1.5 cal default";
					}
					Structures.Ballast b = Structures.ballast(base, d.doc, holder, pos, target);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("target", Units.num(target) + " cal minimum ascent stability (" + basis + ")");
					out.put("location", holder.getName() + " + " + Units.fmt(pos, Dim.LENGTH) + " = " + Units.fmt(b.x(), Dim.LENGTH)
							+ " from the nose tip");
					out.put("ballastMass", Units.fmt(b.mass(), Dim.MASS));
					double launch = Structures.launchMass(fc);
					out.put("fractionOfLaunchMass", Units.num(100 * b.mass() / launch) + "%");
					out.put("staticEstimate", Units.fmt(Math.max(0, b.analyticMass()), Dim.MASS) + " (static margin at Mach 0.3, launch CG)");
					Map<String, Object> eff = new LinkedHashMap<>();
					eff.put("minAscentStability", Units.num(b.baseMinStability()) + " -> " + Units.num(b.minStability()) + " cal");
					eff.put("apogee", Units.fmt(b.baseApogee(), Dim.DISTANCE) + " -> " + Units.fmt(b.apogee(), Dim.DISTANCE));
					eff.put("railExitVelocity", Units.fmt(b.baseRail(), Dim.VELOCITY) + " -> " + Units.fmt(b.rail(), Dim.VELOCITY));
					out.put("effect", eff);
					out.put("simulations", b.simulations());
					if (b.note() != null) {
						out.put("note", b.note());
					}
					if (b.mass() > 0) out.put("nextStep", "add_component {type: MassComponent, parent: \"" + holder.getName() + "\", componentMass: \""
							+ Units.fmt(b.mass(), Dim.MASS).split(" \\(")[0] + "\"} positioned at the location above, then "
							+ "check_requirements. Secure ballast structurally; it must not shift under ejection or landing loads.");
					return out;
				}));
	}
}
