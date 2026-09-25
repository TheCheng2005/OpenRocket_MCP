package io.github.openrocketmcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Aero;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Presets;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.report.Drawing;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Aerodynamics, wind profiles, the parts database and drawings: OpenRocket features beyond the flight simulation. */
public final class AeroTools {
	private AeroTools() {
	}

	static final double[] DEFAULT_MACHS = { 0.1, 0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 1.0, 1.05, 1.1, 1.2, 1.5, 2.0, 2.5, 3.0 };

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("aero_analysis", "Drag and CP vs Mach, drag breakdown",
				"Query OpenRocket's aerodynamic model directly (zero angle of attack): total drag coefficient split into "
						+ "friction / pressure / base drag, CP position, CNalpha and static margin (launch and burnout CG) at a "
						+ "range of Mach numbers (default up to just above the simulated max Mach, or 1.2), plus the drag of each "
						+ "component at one Mach number and OpenRocket's geometry warnings. Use it to see where drag comes from "
						+ "(nose shape, fin airfoil, surface finish, launch lugs / rail buttons) and how the margin shifts "
						+ "transonic.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).str("configuration", DesignTools.CONFIG, false)
						.array("machs", "Mach numbers for the table.", Schema.type("number", null), false)
						.num("breakdownMach", "Mach number for the per-component drag breakdown (default 0.3).", false)
						.num("maxMach", "Highest Mach for the default table (default 1.2).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					FlightConfiguration fc = Components.config(d.doc.getRocket(), a.str("configuration", null));
					double[] machs;
					if (a.has("machs")) {
						machs = a.array("machs").asList().stream().mapToDouble(e -> e.getAsDouble()).toArray();
					} else {
						double top = a.num("maxMach", 1.2);
						machs = java.util.Arrays.stream(DEFAULT_MACHS).filter(m -> m <= top + 1e-9).toArray();
					}
					if (machs.length == 0 || machs.length > 40) {
						throw new ToolException("Give 1-40 Mach numbers.");
					}
					Map<String, Object> out = new LinkedHashMap<>();
					List<Map<String, Object>> table = new ArrayList<>();
					for (Aero.Point p : Aero.sweep(fc, machs)) {
						table.add(Aero.render(p));
					}
					out.put("vsMach", table);
					double bm = a.num("breakdownMach", 0.3);
					out.put("dragBreakdownAtMach", Units.num(bm));
					out.put("dragBreakdown", Aero.breakdown(fc, bm));
					List<String> w = Aero.warnings(fc, bm);
					if (!w.isEmpty()) {
						out.put("openRocketWarnings", w);
					}
					out.put("notes", List.of(
							"Zero angle of attack; Reynolds number from sea-level ISA at each Mach. Drag in flight also varies with "
									+ "altitude (Reynolds) and angle of attack; the simulation uses the full model.",
							"Margins use the maximum body diameter as reference. OpenRocket's Barrowman model is least accurate "
									+ "transonic; LC expects RASAero CP/CD for airframes with diameter changes."));
					return out;
				}));

		s.tool(new ToolDef("wind_profile", "Winds aloft (multi-level wind)",
				"Set, show or clear a wind profile on a simulation, using OpenRocket's multi-level wind model (speed, direction "
						+ "and turbulence per altitude, interpolated between levels). Give levels from a forecast or sounding, or "
						+ "mode power_law to build a shear profile v = v_ground (h / 10 m)^alpha from the ground wind (alpha ~ 1/7 "
						+ "open terrain, ~0.1 over water, higher over rough terrain / stable nights). Every tool then uses the profile; "
						+ "tools that override the wind speed (sweeps, the design-wind check, Monte Carlo) scale and rotate it from "
						+ "its lowest level. mode average returns to a single wind.",
				SimTools.simSelect(Schema.object())
						.enumStr("mode", "levels | power_law | average | show", true, "levels", "power_law", "average", "show")
						.array("levels", "For mode levels: {altitude, speed, direction, sd?} (direction the wind blows FROM).",
								Schema.type("object", null), false)
						.bool("aboveGroundLevel", "Level altitudes are AGL (default true); false = MSL.", false)
						.qty("groundSpeed", "power_law: wind speed at 10 m (default: the simulation's wind speed).", false)
						.qty("direction", "power_law: wind direction (from) (default: the simulation's).", false)
						.num("exponent", "power_law: shear exponent alpha (default 1/7).", false)
						.qty("top", "power_law: highest level (default 3000 m).", false)
						.num("turbulence", "power_law: turbulence as a fraction of each level's speed (default 0.1).", false)
						.bool("allSimulations", "Apply to every simulation of the design (default false).", false).build(),
				false, a -> windProfile(ctx, a)));

		s.tool(new ToolDef("search_parts", "Search the parts database",
				"Search OpenRocket's parts database (manufacturer catalogs) by type: body_tube, nose_cone, transition, "
						+ "tube_coupler, bulk_head, centering_ring, engine_block, launch_lug, rail_button, streamer, parachute. Filter by "
						+ "outer / inner diameter and manufacturer or text (e.g. \"LOC\", \"fiberglass\", \"4 in\"). Apply one with "
						+ "apply_preset.",
				Schema.object().str("type", "Part type, e.g. body_tube.", true)
						.qty("minOuterDiameter", "Minimum outer diameter.", false).qty("maxOuterDiameter", "Maximum outer diameter.", false)
						.qty("minInnerDiameter", "Minimum inner diameter.", false).qty("maxInnerDiameter", "Maximum inner diameter.", false)
						.str("manufacturer", "Manufacturer substring.", false).str("text", "Words that must appear in the part name, description or material.", false)
						.integer("limit", "Maximum results (default 25).", false).build(),
				true, a -> {
					ComponentPreset.Type t = Presets.type(a.str("type"));
					List<ComponentPreset> found = Presets.search(t, a.qtyOrNaN("minOuterDiameter", Dim.LENGTH),
							a.qtyOrNaN("maxOuterDiameter", Dim.LENGTH), a.qtyOrNaN("minInnerDiameter", Dim.LENGTH),
							a.qtyOrNaN("maxInnerDiameter", Dim.LENGTH), a.str("manufacturer", null), a.str("text", null));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("type", t.name());
					out.put("matches", found.size());
					List<Map<String, Object>> rows = new ArrayList<>();
					for (ComponentPreset p : found.subList(0, Math.min(found.size(), Math.max(1, a.integer("limit", 25))))) {
						rows.add(Presets.render(p));
					}
					out.put("parts", rows);
					return out;
				}));

		s.tool(new ToolDef("apply_preset", "Use a catalog part for a component",
				"Load a parts-database preset (\"<manufacturer> <part no>\" from search_parts) into a component: dimensions, "
						+ "material and mass as the catalog gives them. Returns the new properties and stability.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).str("component", "Component id or name.", true)
						.str("preset", "\"<manufacturer> <part no>\", or a unique part number.", true).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					RocketComponent c = Components.find(d.doc.getRocket(), a.str("component"));
					ComponentPreset p = Presets.apply(c, a.str("preset"));
					d.doc.setSaved(false);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("component", c.getName() + " [" + Components.shortId(c) + "]");
					out.put("preset", Presets.render(p));
					out.put("properties", Components.describe(c));
					out.put("stability", Analysis.stageStacks(d.doc.getRocket().getSelectedConfiguration(), 0.3));
					return out;
				}));

		s.tool(new ToolDef("draw_rocket", "Side-profile drawing (SVG)",
				"Draw the active configuration from OpenRocket's geometry (body profiles, fins, pods) with CG at launch and "
						+ "burnout and CP marked, as an SVG file. Good for design reviews and for checking a design built from "
						+ "scratch looks right.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).str("configuration", DesignTools.CONFIG, false)
						.str("path", "Output .svg path (default: rocket.svg in the working directory).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					FlightConfiguration fc = Components.config(d.doc.getRocket(), a.str("configuration", null));
					Path p = Path.of(a.str("path", "rocket.svg")).toAbsolutePath();
					if (p.getParent() != null) {
						Files.createDirectories(p.getParent());
					}
					Files.writeString(p, Drawing.svg(fc, d.name() + " (" + fc.getName() + ")"));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("file", p.toString());
					out.put("stability", Analysis.render(Analysis.stability(fc, 0.3)));
					return out;
				}));
	}

	private static Object windProfile(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
				ctx.standards());
		List<Simulation> targets = a.bool("allSimulations", false) ? d.doc.getSimulations() : List.of(sim);
		String mode = a.str("mode");
		List<Winds.Level> levels = null;
		switch (mode) {
			case "levels" -> {
				levels = new ArrayList<>();
				for (Args l : a.objList("levels")) {
					levels.add(new Winds.Level(l.qty("altitude", Dim.DISTANCE), l.qty("speed", Dim.VELOCITY),
							l.qty("direction", Dim.ANGLE, 0), l.qtyOrNaN("sd", Dim.VELOCITY)));
				}
			}
			case "power_law" -> {
				var o = sim.getOptions();
				double g = a.qty("groundSpeed", Dim.VELOCITY, Winds.speed(o));
				levels = Winds.powerLaw(g, a.qty("direction", Dim.ANGLE, Winds.direction(o)), a.num("exponent", 1.0 / 7),
						a.qty("top", Dim.DISTANCE, 3000), a.num("turbulence", 0.1));
			}
			case "average" -> targets.forEach(t -> Winds.useAverage(t.getOptions()));
			case "show" -> {
				// no change
			}
			default -> throw new ToolException("mode must be levels, power_law, average or show.");
		}
		if (levels != null) {
			boolean agl = a.bool("aboveGroundLevel", true);
			for (Simulation t : targets) {
				Winds.setProfile(t.getOptions(), levels, agl);
			}
		}
		if (!"show".equals(mode)) {
			d.doc.setSaved(false);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("simulation", targets.size() == 1 ? targets.get(0).getName() : targets.size() + " simulations");
		out.put("wind", Winds.describe(sim.getOptions()));
		return out;
	}
}
