package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Library;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** The team's past designs and flights as a searchable library. */
public final class LibraryTools {
	private LibraryTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("design_library", "Search the team's past designs and flights",
				"Point at the folder of the team's past rockets (OpenRocket .ork of any version, RockSim .rkt; sub-folders "
						+ "too) and their altimeter logs (.csv of time and altitude, named after the design or kept in its folder). "
						+ "Every design is summarised without opening it: year (from the folder or file name, e.g. \"2024/\", else "
						+ "the file date), diameter, length, launch mass, motors and impulse class, stages, predicted apogee (the "
						+ "file's saved simulation, or simulated now), stability, airframe and fin materials, recovery. Filter by "
						+ "text, diameter, motor class, year and stages (e.g. 4 in rockets on M motors: minDiameter \"3.9 in\", "
						+ "maxDiameter \"4.1 in\", motorClass \"M\"), rank by closeness to an open design (similarTo), and see "
						+ "predicted vs measured apogee for every matched flight, by year, with the overall bias. Open a past "
						+ "design with open_design and its path.",
				Schema.object().str("folder", "Folder with the past designs and logs (searched recursively).", true)
						.str("text", "Words that must all appear in the name, path, motors, materials or recovery.", false)
						.qty("minDiameter", "Smallest airframe diameter, e.g. \"3.9 in\" or \"98 mm\".", false)
						.qty("maxDiameter", "Largest airframe diameter.", false)
						.str("motorClass", "Total impulse class, e.g. \"M\" or a range \"L-N\".", false)
						.integer("yearFrom", "First year.", false).integer("yearTo", "Last year.", false)
						.integer("stages", "Number of stages.", false)
						.bool("withFlights", "Only designs with a matched flight log.", false)
						.enumStr("sort", "Order of the list (default year, newest first).", false, "year", "name", "apogee", "mass",
								"diameter", "impulse")
						.str("similarTo", "An open design's id: list past designs closest in diameter, mass and impulse instead.",
								false)
						.integer("limit", "Most designs to list (default 25).", false)
						.bool("simulate", "Simulate designs whose file has no saved results (default true).", false).build(),
				true, a -> {
					Library.Scan scan = Library.scan(ctx.path(a.str("folder")), a.bool("simulate", true));
					Map<String, Object> out = new LinkedHashMap<>();
					int ok = 0, minYear = Integer.MAX_VALUE, maxYear = 0;
					for (Library.Entry e : scan.designs()) {
						if (e.error() == null) {
							ok++;
							minYear = Math.min(minYear, e.year());
							maxYear = Math.max(maxYear, e.year());
						}
					}
					out.put("library", ok + " design" + (ok == 1 ? "" : "s") + (ok > 0 ? " (" + (minYear == maxYear ? minYear
							: minYear + "-" + maxYear) + ")" : "") + ", " + scan.flights().size() + " flight log"
							+ (scan.flights().size() == 1 ? "" : "s") + " in " + scan.root());
					int limit = Math.max(1, a.integer("limit", 25));
					List<Library.Entry> list;
					if (a.has("similarTo")) {
						Designs.Design d = ctx.designs.get(a.str("similarTo"));
						FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
						double impulse = 0;
						for (MotorConfiguration mc : fc.getActiveMotors()) {
							if (mc.getMotor() != null) {
								impulse += mc.getMotor().getTotalImpulseEstimate() * Math.max(1, mc.getMotorCount());
							}
						}
						double dia = Analysis.maxDiameter(fc), mass = MassCalculator.calculateLaunch(fc).getMass();
						out.put("similarTo", d.name() + ": " + Units.fmt(dia, Dim.LENGTH) + ", " + Units.fmt(mass, Dim.MASS)
								+ (impulse > 0 ? ", " + Units.fmt(impulse, Dim.IMPULSE) : ", no motor"));
						list = Library.similar(scan, dia, mass, impulse, d.path);
					} else {
						list = Library.filter(scan, new Library.Query(a.str("text", null), a.qtyOrNaN("minDiameter", Dim.LENGTH),
								a.qtyOrNaN("maxDiameter", Dim.LENGTH), a.str("motorClass", null), a.integer("yearFrom", 0),
								a.integer("yearTo", 0), a.integer("stages", 0), a.bool("withFlights", false), a.str("sort", null)));
					}
					out.put("matches", list.size());
					List<Map<String, Object>> rows = new ArrayList<>();
					for (Library.Entry e : list.subList(0, Math.min(limit, list.size()))) {
						rows.add(Library.render(scan, e));
					}
					out.put("designs", rows);
					if (list.size() > limit) {
						out.put("more", (list.size() - limit) + " more; narrow the search or raise limit");
					}
					if (!scan.flights().isEmpty()) {
						out.put("predictedVsMeasured", Library.trend(scan, list));
					}
					if (!scan.skipped().isEmpty()) {
						out.put("skipped", scan.skipped());
					}
					out.put("next", "open_design with a design's path to reuse it (save_design under a new name first); "
							+ "compare_designs against the current rocket; compare_flight to re-fly a log in that day's conditions.");
					return out;
				}));
	}
}
