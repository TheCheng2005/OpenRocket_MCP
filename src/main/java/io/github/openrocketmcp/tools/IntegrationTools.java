package io.github.openrocketmcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.FinFea;
import io.github.openrocketmcp.or.Geometry;
import io.github.openrocketmcp.report.Png;
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

		s.tool(new ToolDef("fin_fea", "Finite-element check of a fin (CalculiX)",
				"Build a finite-element model of one fin and, when CalculiX is installed (free; the executable is found as $CCX "
						+ "or ccx on the PATH), run it: 8-node shell plate clamped along the root, carrying the aerodynamic load of "
						+ "the worst flight point (the larger of a crosswind gust at max q and the largest simulated q x angle of "
						+ "attack; fin normal force from OpenRocket's fin model on the most loaded fin) as a uniform pressure. "
						+ "Returns tip deflection, peak stress against the material strength with the team's safety factor, and "
						+ "the first natural frequencies with their mode (bending / torsion), next to hand estimates. The input "
						+ "deck (.inp, also readable by Abaqus) is always written so it can be refined or run elsewhere. Material "
						+ "constants come from the team standards (structures.youngsModulus, shearModulus, poissonRatio, "
						+ "strength) unless given.",
				SimTools.simSelect(Schema.object())
						.str("finSet", "Fin set id or name (default: the first).", false)
						.qty("thickness", "What-if thickness, without editing the design.", false)
						.qty("youngsModulus", "In-plane Young's modulus, e.g. \"55 GPa\".", false)
						.qty("shearModulus", "In-plane shear modulus, e.g. \"16 GPa\" (quasi-isotropic carbon).", false)
						.num("poissonRatio", "In-plane Poisson's ratio.", false)
						.qty("allowableStress", "Allowable stress (yield for metals, failure stress for laminates).", false)
						.num("safetyFactor", "Safety factor on the flight load (default structures.loadSafetyFactor).", false)
						.qty("gustSpeed", "Crosswind gust at max q (default: the rule set's maximum ground wind).", false)
						.qty("pressure", "Use this uniform pressure instead of the flight load (e.g. a ground-test load).", false)
						.integer("modes", "Natural frequencies to compute (default 4).", false)
						.str("outDir", "Folder for the deck and results (default \"<design>-fea\").", false)
						.str("plotPath", "Also draw the fin coloured by stress (SVG), e.g. \"plots/fin-stress.svg\".", false)
						.bool("run", "Run CalculiX when it is available (default true).", false).build(),
				false, a -> fea(ctx, a)));
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
		Path preview = dir.resolve("preview.svg");
		Files.writeString(preview, Geometry.previewSvg(Geometry.solids(fc, 36), d.name() + ": CFD model (STL regions)"));
		files.put("preview", preview.toAbsolutePath() + " (3-D view of the regions)");
		Png.attachFile(preview, "CFD model regions");
		files.put("stl", stl.toAbsolutePath() + " (" + tris + " triangles, " + units + "; regions: " + String.join(", ", regions) + ")");

		List<String> dxfs = new ArrayList<>();
		for (RocketComponent c : fc.getAllActiveComponents()) {
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
			r.put("reynolds", String.format(Locale.ROOT, "%.3g", c.reynolds()));
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
				"Fill " + tpl.getFileName() + " and call import_aero_table with path = that file and cpUnit = \"m\": it shows the CFD "
						+ "values next to OpenRocket's and their effect on apogee and stability, and every later simulation and "
						+ "check_requirements uses them (optimizers that change the shape fall back to OpenRocket's model).",
				"Transonic (Mach 0.9-1.2) drag and the fin-body CP are where CFD differs most from OpenRocket's Barrowman "
						+ "model; if time is short, run those Mach numbers first."));
		out.put("notes", List.of(
				"Fins are flat plates with square edges at their true thickness; airfoiled or rounded edges and fillets are not "
						+ "modelled. Rail buttons, launch lugs and surface finish are left out: add them in CAD if they matter.",
				"The STL regions share their seams, so a mesher can report the force on each part (e.g. nose, fins, base)."));
		return out;
	}

	private static Object fea(Context ctx, Args a) throws Exception {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = SimTools.runSelected(ctx, a);
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		FinSet fin = null;
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (c instanceof FinSet f && (!a.has("finSet") || f == Components.find(sim.getRocket(), a.str("finSet")))) {
				fin = f;
				break;
			}
		}
		if (fin == null) {
			throw new ToolException(a.has("finSet") ? "No active fin set '" + a.str("finSet") + "'." : "The active configuration has no fin sets.");
		}
		var std = ctx.standards();
		String matName = fin.getMaterial().getName();
		List<String> sources = new ArrayList<>();
		double g = a.qtyOrNaN("shearModulus", Dim.PRESSURE);
		if (Double.isNaN(g)) {
			Object[] v = std.shearModulus(matName);
			if (v == null) {
				throw new ToolException("No shear modulus for fin material '" + matName + "'. Pass shearModulus and youngsModulus, or "
						+ "add the material to structures.shearModulus / youngsModulus in the team standards.");
			}
			g = (Double) v[0];
			sources.add("G from standards (" + v[1] + ")");
		}
		double nu = a.num("poissonRatio", Double.NaN);
		if (Double.isNaN(nu)) {
			Object[] v = std.materialValue("structures.poissonRatio", matName, Dim.DIMENSIONLESS);
			nu = v == null ? 0.3 : (Double) v[0];
			sources.add(v == null ? "Poisson's ratio 0.3 (assumed)" : "Poisson's ratio from standards (" + v[1] + ")");
		}
		double e = a.qtyOrNaN("youngsModulus", Dim.PRESSURE);
		if (Double.isNaN(e)) {
			Object[] v = std.materialValue("structures.youngsModulus", matName, Dim.PRESSURE);
			e = v == null ? 2 * g * (1 + nu) : (Double) v[0];
			sources.add(v == null ? "E = 2 G (1 + nu) (isotropic assumption: give youngsModulus for a laminate)"
					: "E from standards (" + v[1] + ")");
		}
		double strength = a.qtyOrNaN("allowableStress", Dim.PRESSURE);
		if (Double.isNaN(strength)) {
			Object[] v = std.materialValue("structures.strength", matName, Dim.PRESSURE);
			if (v != null) {
				strength = (Double) v[0];
				sources.add("strength from standards (" + v[1] + ")");
			}
		}
		boolean metal = matName.toLowerCase(Locale.ROOT).matches(".*(alumin|steel|titanium|6061|7075).*");
		double sf = a.num("safetyFactor", std.q("structures.loadSafetyFactor", Dim.DIMENSIONLESS, 2));
		FinFea.Material m = new FinFea.Material(e, g, nu, fin.getMaterial().getDensity(), strength, metal);
		FinFea.Plate plate = FinFea.plate(fin, a.qtyOrNaN("thickness", Dim.LENGTH));

		double gust = a.qty("gustSpeed", Dim.VELOCITY, Double.isNaN(std.rule("maxGroundWind.value", Dim.VELOCITY)) ? 30 / 3.6
				: std.rule("maxGroundWind.value", Dim.VELOCITY));
		FinFea.Load load = FinFea.designLoad(sim, fin, gust);
		double pressure = a.has("pressure") ? a.qty("pressure", Dim.PRESSURE) : load.finForce() / plate.area();
		double force = pressure * plate.area();

		int modes = Math.max(1, Math.min(10, a.integer("modes", 4)));
		FinFea.Deck deck = FinFea.deck(plate, m, pressure, 16, 12, modes);
		Path dir = ctx.path(a.str("outDir", Geometry.safe(d.name()) + "-fea"));
		Files.createDirectories(dir);
		String job = "fin-" + Geometry.safe(fin.getName());
		Path inp = dir.resolve(job + ".inp");
		Files.writeString(inp, deck.text());

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("finSet", fin.getName() + ", " + fin.getFinCount() + " fins, " + matName);
		Map<String, Object> geo = new LinkedHashMap<>();
		geo.put("rootChord", Units.fmt(plate.root(), Dim.LENGTH));
		geo.put("tipChord", Units.fmt(plate.tip(), Dim.LENGTH));
		geo.put("span", Units.fmt(plate.span(), Dim.LENGTH));
		geo.put("sweep", Units.fmt(plate.sweep(), Dim.LENGTH));
		geo.put("thickness", Units.fmt(plate.thickness(), Dim.LENGTH));
		if (plate.note() != null) {
			geo.put("note", plate.note());
		}
		out.put("fin", geo);
		Map<String, Object> mat = new LinkedHashMap<>();
		mat.put("youngsModulus", Units.fmt(e, Dim.PRESSURE));
		mat.put("shearModulus", Units.fmt(g, Dim.PRESSURE));
		mat.put("poissonRatio", Units.num(nu));
		mat.put("density", Units.num(m.density()) + " kg/m3 (OpenRocket material)");
		if (!Double.isNaN(strength)) {
			mat.put("allowableStress", Units.fmt(strength, Dim.PRESSURE));
		}
		mat.put("sources", sources);
		out.put("material", mat);
		Map<String, Object> ld = new LinkedHashMap<>();
		if (a.has("pressure")) {
			ld.put("basis", "given pressure");
		} else {
			ld.put("basis", load.basis() + " at t=" + Units.num(load.time()) + " s, Mach " + Units.num(load.mach()) + ", q "
					+ Units.fmt(load.q(), Dim.PRESSURE) + ", angle of attack " + Units.num(Math.toDegrees(load.alpha())) + " deg");
		}
		ld.put("normalForceOnFin", Units.fmt(force, Dim.FORCE) + " (limit load; the most loaded fin)");
		ld.put("pressure", Units.fmt(pressure, Dim.PRESSURE) + " uniform over " + Units.fmt(plate.area(), Dim.AREA));
		ld.put("rootBendingMoment", Units.num(force * plate.centroidSpan()) + " N·m");
		ld.put("safetyFactor", Units.num(sf));
		out.put("load", ld);

		Map<String, Object> hand = new LinkedHashMap<>();
		double sHand = FinFea.rootStress(plate, force);
		hand.put("rootBendingStress", Units.fmt(sHand, Dim.PRESSURE) + " (6 M / (root chord t^2))");
		hand.put("firstBendingFrequency", Units.num(FinFea.bendingFrequency(plate, m)) + " Hz (uniform cantilever of the fin's span)");
		out.put("handEstimate", hand);

		String ccx = FinFea.findCcx(null);
		Map<String, Object> files = new LinkedHashMap<>();
		files.put("deck", inp.toAbsolutePath().toString());
		if (ccx != null && a.bool("run", true)) {
			FinFea.Result r = FinFea.run(ccx, dir, job, deck, 300);
			files.put("results", dir.resolve(job + ".frd").toAbsolutePath() + " (open in CalculiX GraphiX, PrePoMax or ParaView)");
			Map<String, Object> fe = new LinkedHashMap<>();
			fe.put("solver", ccx);
			fe.put("mesh", deck.elements() + " S8R shells (16 chordwise x 12 spanwise)");
			fe.put("maxDeflection", Units.fmt(r.maxDeflection(), Dim.LENGTH) + " (" + Units.num(100 * r.maxDeflection() / plate.span())
					+ "% of span)");
			double governing = metal ? r.vonMisesAwayFromCorners() : r.principalAwayFromCorners();
			String measure = metal ? "von Mises" : "largest principal";
			fe.put("peakStress", Units.fmt(governing, Dim.PRESSURE) + " (" + measure + ", root region away from the corners)");
			fe.put("cornerPeakStress", Units.fmt(metal ? r.maxVonMises() : r.maxPrincipal(), Dim.PRESSURE) + " at a root corner, "
					+ "where the ideal clamp concentrates stress (it grows as the mesh is refined); a root fillet or tab spreads "
					+ "it in the real fin");
			if (!Double.isNaN(strength)) {
				double margin = strength / (sf * governing) - 1;
				fe.put("marginOfSafety", Units.num(margin) + " (" + measure + " stress vs " + (metal ? "yield" : "strength") + ", x"
						+ Units.num(sf) + ")");
				fe.put("status", margin >= 0 ? "PASS" : "FAIL: stress x safety factor exceeds the allowable");
			} else {
				fe.put("status", "INFO: no allowable stress for " + matName + " (give allowableStress or set structures.strength)");
			}
			List<String> ms = new ArrayList<>();
			for (FinFea.Mode md : r.modes()) {
				ms.add(Units.num(md.frequency()) + " Hz (" + md.kind() + ")");
			}
			fe.put("naturalFrequencies", ms);
			fe.put("vsHandEstimate", "root stress " + Units.num(governing / sHand) + "x the plate-strip estimate"
					+ (r.modes().isEmpty() ? "" : ", 1st mode " + Units.num(r.modes().get(0).frequency() / FinFea.bendingFrequency(plate, m))
							+ "x the cantilever estimate"));
			double fb = Double.NaN, ft = Double.NaN;
			for (FinFea.Mode md : r.modes()) {
				if (Double.isNaN(fb) && md.kind().contains("1st bending")) {
					fb = md.frequency();
				}
				if (Double.isNaN(ft) && md.kind().contains("torsion")) {
					ft = md.frequency();
				}
			}
			if (!Double.isNaN(fb) && !Double.isNaN(ft)) {
				fe.put("torsionToBendingRatio", Units.num(ft / fb) + " (flutter needs the torsion and bending modes to couple; "
						+ "the closer this is to 1, the lower the flutter speed)");
			}
			if (a.has("plotPath")) {
				Path pp = ctx.path(a.str("plotPath"));
				if (pp.getParent() != null) {
					Files.createDirectories(pp.getParent());
				}
				Files.writeString(pp, FinFea.stressSvg(deck, metal ? r.vonMisesGrid() : r.principalGrid(), d.name() + ": " + fin.getName()
						+ " under the design load", metal ? "von Mises" : "largest principal", Double.isNaN(strength) ? Double.NaN : strength / sf));
				files.put("stressPlot", pp.toAbsolutePath().toString());
				Png.attachFile(pp, "Fin stress under the design load");
			}
			out.put("fea", fe);
		} else {
			out.put("fea", ccx == null ? "CalculiX not found, so the deck was written but not run. Install it (Linux: apt install "
					+ "calculix-ccx; Windows: the ccx bundled with PrePoMax; macOS / any: conda install -c conda-forge calculix) "
					+ "and set CCX to its path if it is not on the PATH, then ask again; or run the deck with 'ccx -i " + job + "'."
					: "Not run (run=false).");
		}
		out.put("files", files);
		out.put("notes", List.of(
				"Root fully clamped (a well-bonded through-the-wall fin); surface-mounted fins with fillets are more flexible "
						+ "and the fillet, not the fin, often fails first.",
				"Uniform pressure: the real load peaks near the leading edge subsonically, so torsion loads are underestimated; "
						+ "import a CFD pressure map into the deck (*DLOAD per element) for more.",
				"A homogeneous plate with the laminate's in-plane constants: use a layup-specific model (*SHELL SECTION, "
						+ "COMPOSITE) for ply-level failure. Check flutter with fin_flutter."));
		return out;
	}
}
