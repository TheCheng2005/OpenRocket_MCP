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
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.FlightLog;
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

		s.tool(new ToolDef("import_aero_table", "Use RASAero (or other) CD / CP data",
				"Import an aerodynamic table (RASAero II 'export aero data' CSV, or any CSV with Mach, CD [, CP]) for a design. The "
						+ "drag replaces OpenRocket's in every simulation (power-on / power-off), the CP is used for a stability check "
						+ "against the simulated CG (Launch Canada asks for RASAero CP/CD when the airframe diameter changes). Shows the "
						+ "table next to OpenRocket's own CD / CP and the effect on apogee and minimum stability. mode clear / show. "
						+ "save_design keeps the table next to the .ork (rocket.aero.json) and open_design loads it again.",
				SimTools.simSelect(Schema.object())
						.enumStr("mode", "import (default) | show | clear", false, "import", "show", "clear")
						.str("path", "CSV file path.", false).str("csv", "CSV text (instead of path).", false)
						.str("cpUnit", "Unit of the CP column, measured from the nose tip (default in, as RASAero writes).", false)
						.bool("useDrag", "Apply the table's drag to simulations (default true).", false)
						.str("source", "Label for CSV text, e.g. \"RASAero rev B\".", false).build(),
				false, a -> importAeroTable(ctx, a)));

		s.tool(new ToolDef("compare_flight", "Compare an altimeter log with the simulation",
				"Read an altimeter CSV (time, altitude; units from the header or altitudeUnit) and compare apogee, time to "
						+ "apogee and descent rates under drogue and main with the simulation, then fit the drag multiplier that "
						+ "reproduces the measured apogee (model calibration for the next flight). Optionally writes an overlay plot. "
						+ "Set the day's conditions with the wind / launch overrides.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("path", "Altimeter CSV path.", false).str("csv", "CSV text (instead of path).", false)
						.str("altitudeUnit", "ft or m when the header does not say (default: header, else ft).", false)
						.str("plotPath", "Write a simulated-vs-measured altitude SVG here.", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					FlightLog.Log log = FlightLog.parse(readText(ctx, a, "path", "csv"), a.str("altitudeUnit", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), SimTools.overrides(a),
							ctx.standards(), false);
					return FlightLog.compare(base, d.doc, log, a.has("plotPath") ? ctx.path(a.str("plotPath")).toString() : null, d.name() + ": simulated vs measured");
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
					Path p = ctx.path(a.str("path", "rocket.svg")).toAbsolutePath();
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

	static String readText(Context ctx, Args a, String pathKey, String textKey) throws java.io.IOException {
		if (a.has(pathKey)) {
			return Files.readString(ctx.path(a.str(pathKey)));
		}
		if (a.has(textKey)) {
			return a.str(textKey);
		}
		throw new ToolException("Give " + pathKey + " (a file) or " + textKey + " (the CSV text).");
	}

	private static Object importAeroTable(Context ctx, Args a) throws java.io.IOException {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		var rocket = d.doc.getRocket();
		String mode = a.str("mode", "import");
		if (mode.equals("clear")) {
			AeroTable.clear(rocket);
			return Map.of("cleared", true, "note", "Simulations use OpenRocket's own drag again.");
		}
		AeroTable.Table t;
		if (mode.equals("show")) {
			t = AeroTable.of(rocket);
			if (t == null) {
				return Map.of("table", "none imported");
			}
		} else {
			String text = readText(ctx, a, "path", "csv");
			double cpUnit = Units.toSi("1 " + a.str("cpUnit", "in"), Dim.LENGTH);
			t = AeroTable.parse(text, a.has("path") ? ctx.path(a.str("path")).getFileName().toString() : a.str("source", "imported table"),
					cpUnit, a.bool("useDrag", true));
			AeroTable.set(rocket, t);
		}
		FlightConfiguration fc = Components.config(rocket, a.str("configuration", null));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("table", AeroTable.describe(t));
		// Side by side with OpenRocket's own model.
		List<Map<String, Object>> cmp = new ArrayList<>();
		double[] ms = { 0.3, 0.6, 0.8, 0.95, 1.1, 1.5, 2.0 };
		double top = t.mach()[t.mach().length - 1];
		for (double m : ms) {
			if (m > top + 1e-9) {
				continue;
			}
			Aero.Point p = Aero.sweep(fc, new double[] { m }).get(0);
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("mach", Units.num(m));
			r.put("cdImportedPowerOff", Units.num(t.cd(m, false)));
			r.put("cdImportedPowerOn", Units.num(t.cd(m, true)));
			r.put("cdOpenRocket", Units.num(p.cd()));
			if (t.hasCp()) {
				r.put("cpImported", Units.fmt(t.cpAt(m), Dim.LENGTH));
			}
			r.put("cpOpenRocket", Units.fmt(p.cpX(), Dim.LENGTH));
			cmp.add(r);
		}
		out.put("vsOpenRocket", cmp);
		// Effect on the flight.
		Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
				ctx.standards(), false);
		List<io.github.openrocketmcp.or.Variants.Run> runs = io.github.openrocketmcp.or.Variants.runAll(List.of(
				AeroTable.without(io.github.openrocketmcp.or.Variants.of(base, d.doc, null, null)),
				io.github.openrocketmcp.or.Variants.of(base, d.doc, null, null)));
		if (runs.get(0).ok() && runs.get(1).ok()) {
			Map<String, Object> eff = new LinkedHashMap<>();
			eff.put("apogeeOpenRocketDrag", Units.fmt(runs.get(0).sim().getSimulatedData().getMaxAltitude(), Dim.DISTANCE));
			eff.put("apogeeImportedDrag", t.useDrag() ? Units.fmt(runs.get(1).sim().getSimulatedData().getMaxAltitude(), Dim.DISTANCE)
					: "not applied (useDrag false)");
			AeroTable.Margin mImp = AeroTable.minMargin(runs.get(1).sim(), t, Analysis.maxDiameter(fc));
			if (mImp != null) {
				eff.put("minAscentStabilityImportedCp", Units.num(mImp.min()) + " cal at Mach " + Units.num(mImp.mach()));
			}
			var w = io.github.openrocketmcp.or.Sims.ascentStability(runs.get(0).sim().getSimulatedData().getBranch(0));
			if (w != null) {
				eff.put("minAscentStabilityOpenRocketCp", Units.num(w.min()) + " cal");
			}
			out.put("effect", eff);
		}
		out.put("notes", List.of(
				"Drag replaces OpenRocket's axial CD in every simulation of this design (power-on while a motor burns) until the "
						+ "first stage separation; afterwards OpenRocket's model applies (import one table per configuration you fly).",
				"The table is kept for this session; re-import after reopening the design. check_requirements adds a stability "
						+ "item with the imported CP (DTEG R10.3.1 for airframes with diameter changes)."));
		return out;
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
