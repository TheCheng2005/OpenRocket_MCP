package io.github.openrocketmcp.tools;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Loads;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sections;
import io.github.openrocketmcp.or.Shapes;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.units.Dim;

/** Design studies: aerodynamic shape trade, recovery sections, structural loads. */
public final class StudyTools {
	private StudyTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("compare_shapes", "Nose cone and fin shape trade study",
				"Fly the design with every nose cone profile (conical, tangent ogive, elliptical, 1/2 and 3/4 power, parabolic, "
						+ "1/2 parabola, Von Karman, LV-Haack; optionally at several nose lengths) and every fin edge profile "
						+ "(square, rounded, airfoil), then the best nose with the best fins. Returns apogee, change vs current, CD "
						+ "at the design Mach, minimum ascent stability, launch mass, plus shape guidance. Does not edit the design.",
				SimTools.simSelect(Schema.object())
						.str("noseCone", "Nose cone id or name (default: the first nose cone).", false)
						.array("noseLengths", "Nose lengths to try (each with every profile), e.g. [\"16 in\", \"20 in\"].",
								Schema.quantityItem(), false)
						.bool("fins", "Also compare fin edge profiles (default true).", false)
						.num("designMach", "Mach number for the CD column (default: the current design's simulated max Mach).", false)
						.num("minStability", "Flag options below this minimum ascent stability (default: the rule-set floor).", false)
						.build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
							ctx.standards(), false);
					NoseCone nose;
					if (a.has("noseCone")) {
						nose = Components.find(base.getRocket(), a.str("noseCone"), NoseCone.class, "nose cone");
					} else {
						nose = null;
						for (RocketComponent c : base.getRocket()) {
							if (c instanceof NoseCone nc) {
								nose = nc;
								break;
							}
						}
						if (nose == null) {
							throw new ToolException("The design has no nose cone.");
						}
					}
					double minStab = a.num("minStability", Double.NaN);
					if (Double.isNaN(minStab)) {
						double f = Requirements.stabilityFloor(ctx.standards(),
								base.getRocket().getFlightConfiguration(base.getFlightConfigurationId()))[0];
						minStab = f > 0 ? f : Double.NaN;
					}
					return Shapes.study(base, d.doc, nose, a.has("noseLengths") ? a.qtyList("noseLengths", Dim.LENGTH) : null,
							a.bool("fins", true), a.num("designMach", Double.NaN), minStab);
				}));

		s.tool(new ToolDef("recovery_sections", "Recovery sections from the design",
				"Split the airframe into independently landing, tethered sections (automatically at the bays holding recovery "
						+ "devices, or at the joints you name) and report each section's landing mass (components plus burnt-out "
						+ "motor cases), simulated landing velocity and kinetic energy against the limit, the energy if the main "
						+ "fails, and each bay's packed recovery volume, free volume, fill and a black powder estimate.",
				SimTools.simSelect(Schema.object())
						.array("joints", "Airframe pieces (names or ids) whose FORWARD end separates; default automatic.",
								Schema.type("string", null), false).build(),
				false, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					Set<String> joints = a.has("joints") ? new LinkedHashSet<>(a.strList("joints")) : null;
					return Sections.analyze(sim, ctx.standards(), joints);
				}));

		s.tool(new ToolDef("structural_loads", "Flight loads at the airframe joints",
				"Quasi-static rigid-body loads at every joint between airframe pieces: the largest axial compression during "
						+ "boost and coast, and the bending moment at maximum dynamic pressure with a crosswind gust (and with the "
						+ "simulated angle of attack), with inertial relief. Converts them to thin-wall tube stress and the required "
						+ "allowable with the team's safety factor. Use the forces to size couplers, fasteners and bulkheads.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.qty("gustSpeed", "Crosswind gust at max q (default: the rule set's maximum ground wind, 30 km/h).", false)
						.num("safetyFactor", "Safety factor on stress (default structures.loadSafetyFactor, 2).", false)
						.qty("allowableStress", "Allowable stress of the tube material, e.g. \"200 MPa\", for a margin.", false).build(),
				true, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					double gust = a.qty("gustSpeed", Dim.VELOCITY,
							orDefault(ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY), 30 / 3.6));
					double sf = a.num("safetyFactor", ctx.standards().q("structures.loadSafetyFactor", Dim.DIMENSIONLESS, 2));
					Map<String, Object> out = Loads.analyze(sim, gust, sf, a.qtyOrNaN("allowableStress", Dim.PRESSURE));
					return out;
				}));
	}

	static double orDefault(double v, double d) {
		return Double.isNaN(v) ? d : v;
	}

}
