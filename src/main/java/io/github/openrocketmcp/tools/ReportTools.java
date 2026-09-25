package io.github.openrocketmcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.report.Reports;
import io.github.openrocketmcp.units.Dim;

/** Design-review reports and data export. */
public final class ReportTools {
	private ReportTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("generate_report", "Write a design-review report",
				"Simulate and write a design-review package to a folder: report.md (requirement checks with rule references, "
						+ "stability per stage stack, motors, flight results, recovery chain, methods and assumptions), the two "
						+ "stability-vs-time plots Launch Canada asks for (stability-to-rail-exit.svg, stability-ascent.svg; "
						+ "DTEG R10.3.2) and flight-data.csv. Good for design reviews and team documentation.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("outputDir", "Folder to write into (created if needed), e.g. \"reports/2026-sustainer\".", true)
						.str("title", "Report title.", false)
						.str("pinType", "Shear pin type from the team standards for pin sizing.", false)
						.qty("pinStrength", "Shear strength of one pin (overrides pinType).", false)
						.bool("includeWindCase", "Also check at the rule set's maximum wind (default true).", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation sim = SimTools.runSelected(ctx, a);
					Simulation wind = null;
					double maxWind = ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY);
					if (a.bool("includeWindCase", true) && !Double.isNaN(maxWind)) {
						wind = sim.copy();
						io.github.openrocketmcp.or.Winds.setGround(wind.getOptions(), maxWind, Double.NaN);
						Sims.run(wind);
					}
					String pinName = a.has("pinType") && !a.has("pinStrength") ? a.str("pinType") : null;
					List<Path> files = Reports.write(d, sim, wind, ctx.standards(), RecoveryTools.pinStrength(ctx, a), pinName,
							a.str("title", null), Path.of(a.str("outputDir")).toAbsolutePath().normalize());
					List<String> names = new ArrayList<>();
					files.forEach(p -> names.add(p.toString()));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("files", names);
					out.put("note", "report.md references the SVG plots by relative path; keep the folder together.");
					return out;
				}));

		s.tool(new ToolDef("export_flight_data", "Export flight data to CSV",
				"Write a simulation's full-resolution time series to a CSV file (display units in the header) for plotting in "
						+ "a spreadsheet, MATLAB or Python, or for comparing with altimeter logs. Variables as in get_flight_data; "
						+ "default: altitude, velocities, acceleration, Mach, stability, CG/CP, thrust, drag, mass, AoA, density.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("path", "CSV file to write.", true)
						.str("branch", "Stage branch name or index (default 0).", false)
						.array("variables", "Flight variables (time is always included).", Schema.type("string", null), false).build(),
				false, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					List<String> vars = a.has("variables") ? a.strList("variables")
							: List.of("altitude", "velocitytotal", "velocityz", "accelerationtotal", "machnumber", "stability",
									"cglocation", "cplocation", "thrustforce", "dragforce", "mass", "aoa", "airdensity");
					Path p = Reports.csv(sim, a.str("branch", null), vars, Path.of(a.str("path")));
					return Map.of("written", p.toString());
				}));
	}
}
