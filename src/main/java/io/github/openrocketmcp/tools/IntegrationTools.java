package io.github.openrocketmcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Geometry;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Hand-offs to CFD, FEA and manufacturing tools, and the way back. */
public final class IntegrationTools {
	private IntegrationTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("export_geometry", "Export geometry and flight conditions for CFD / CAD / manufacturing",
				"Write the rocket's outer shape as STL (one named region per body part, the base and each fin set; watertight "
						+ "body, fins sunk into it) for CFD meshers (OpenFOAM snappyHexMesh, SimScale, Ansys Fluent, STAR-CCM+) or "
						+ "CAD; each fin set's flat pattern as DXF (with its tab) for waterjet, laser or CNC; and, from the simulated "
						+ "flight, the CFD run matrix: a Mach sweep up to the maximum Mach at the altitude and air state where the "
						+ "flight reaches each Mach, plus max q and max Mach, with velocity, pressure, temperature, density, "
						+ "viscosity, Reynolds number and OpenRocket's CD / CP to compare against. Also writes the results "
						+ "template that import_aero_table reads back, so CFD drag and CP drive every later simulation and "
						+ "stability check.",
				SimTools.simSelect(Schema.object())
						.str("outDir", "Folder to write into (default \"<design name>-geometry\").", false)
						.enumStr("units", "STL and DXF units (default mm; OpenFOAM expects m).", false, "mm", "m")
						.integer("segments", "Facets around the body (default 96).", false)
						.array("angleOfAttack", "Angles of attack for the run matrix (default [0, 2, 4] deg; 0 gives CD, the "
								+ "others CN-alpha and CP).", Schema.quantityItem(), false).build(),
				false, a -> export(ctx, a)));
	}

	private static Object export(Context ctx, Args a) throws Exception {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = SimTools.runSelected(ctx, a);
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		String units = a.str("units", "mm");
		double scale = units.equals("m") ? 1 : 1000;
		int seg = Math.max(24, Math.min(360, a.integer("segments", 96)));
		Path dir = ctx.path(a.str("outDir", Geometry.safe(d.name()) + "-geometry"));
		Files.createDirectories(dir);
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> files = new LinkedHashMap<>();

		List<Geometry.Solid> solids = Geometry.solids(fc, seg);
		if (solids.isEmpty()) {
			throw new ToolException("The active configuration has no body parts to export.");
		}
		Path stl = dir.resolve("rocket.stl");
		Files.writeString(stl, Geometry.stl(solids, scale));
		List<String> regions = new ArrayList<>();
		int tris = 0;
		for (Geometry.Solid sd : solids) {
			regions.add(sd.name);
			tris += sd.tris.size();
		}
		files.put("stl", stl.toAbsolutePath() + " (" + tris + " triangles, " + units + "; regions: " + String.join(", ", regions) + ")");

		List<String> dxfs = new ArrayList<>();
		for (RocketComponent c : fc.getActiveComponents()) {
			if (c instanceof FinSet f) {
				Path p = dir.resolve("fin-" + Geometry.safe(f.getName()) + ".dxf");
				Files.writeString(p, Geometry.dxf(f, scale));
				dxfs.add(p.toAbsolutePath() + " (" + f.getFinCount() + " to cut, " + Units.fmt(f.getThickness(), Dim.LENGTH) + " "
						+ f.getMaterial().getName() + (f.getTabHeight() > 1e-9 && f.getTabLength() > 1e-9 ? ", with tab" : "") + ")");
			}
		}
		if (!dxfs.isEmpty()) {
			files.put("finPatterns", dxfs);
		}

		List<Double> aoa = a.has("angleOfAttack") ? a.qtyList("angleOfAttack", Dim.ANGLE) : List.of(0.0, Math.toRadians(2), Math.toRadians(4));
		double[] aoaDeg = aoa.stream().mapToDouble(Math::toDegrees).toArray();
		List<Geometry.Case> cases = Geometry.cases(sim, fc);
		Path csv = dir.resolve("cfd-cases.csv");
		Files.writeString(csv, Geometry.casesCsv(cases, aoaDeg));
		files.put("runMatrix", csv.toAbsolutePath() + " (" + cases.size() * aoaDeg.length + " runs)");
		Path tpl = dir.resolve("cfd-results.csv");
		if (!Files.exists(tpl)) { // never overwrite results the team has filled in
			Files.writeString(tpl, Geometry.resultsTemplate(cases));
		}
		files.put("resultsTemplate", tpl.toAbsolutePath() + " (fill CD and CP, then import_aero_table)");
		out.put("files", files);

		Map<String, Object> ref = new LinkedHashMap<>();
		double dRef = Analysis.maxDiameter(fc);
		ref.put("frame", "x along the axis from the nose tip (0) toward the tail; freestream flows in +x; y, z across");
		ref.put("referenceArea", Units.fmt(Math.PI * dRef * dRef / 4, Dim.AREA) + " (maximum body cross-section, as OpenRocket and "
				+ "RASAero use)");
		ref.put("referenceLength", Units.fmt(dRef, Dim.LENGTH) + " (maximum body diameter)");
		ref.put("bodyLength", Units.fmt(fc.getLength(), Dim.LENGTH));
		ref.put("momentReference", "CG at launch " + Units.fmt(MassCalculator.calculateLaunch(fc).getCM().x, Dim.LENGTH)
				+ ", at burnout " + Units.fmt(MassCalculator.calculateBurnout(fc).getCM().x, Dim.LENGTH) + " from the nose tip");
		out.put("reference", ref);

		List<Map<String, Object>> rows = new ArrayList<>();
		for (Geometry.Case c : cases) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("case", c.name());
			r.put("mach", Units.num(c.mach()));
			r.put("altitude", Units.fmt(c.altitude(), Dim.DISTANCE));
			r.put("velocity", Units.fmt(c.velocity(), Dim.VELOCITY));
			r.put("reynolds", String.format(java.util.Locale.ROOT, "%.3g", c.reynolds()));
			r.put("openrocketCd", Units.num(c.orCd()));
			rows.add(r);
		}
		out.put("cases", rows);
		out.put("roundTrip", List.of(
				"Run each case in CFD at the listed Mach, pressure and temperature (angle of attack from the CSV). Use a "
						+ "density-based / compressible solver from Mach 0.7 up (OpenFOAM rhoCentralFoam, Fluent density-based); "
						+ "pressure-based is fine below.",
				"CD = axial force / (q x reference area) at 0 deg: power-off with a closed base; power-on without base drag "
						+ "(or leave it blank to reuse power-off). CP from the pitching moment at 2-4 deg: x_cp = x_ref - "
						+ "M_ref / N, in metres from the nose tip.",
				"Fill " + tpl.getFileName() + " and call import_aero_table with path = that file and cpUnit = \"m\". Every simulation, "
						+ "check_requirements and optimize_fins then use the CFD drag and CP; aero_analysis shows them next to "
						+ "OpenRocket's.",
				"Transonic (Mach 0.9-1.2) drag and the fin-body CP are where CFD differs most from OpenRocket's Barrowman "
						+ "model; if time is short, run those Mach numbers first."));
		out.put("notes", List.of(
				"Fins are flat plates with square edges at their true thickness; airfoiled or rounded edges and fillets are not "
						+ "modelled. Rail buttons, launch lugs and surface finish are left out: add them in CAD if they matter.",
				"The STL regions share their seams, so a mesher can report the force on each part (e.g. nose, fins, base)."));
		return out;
	}
}
