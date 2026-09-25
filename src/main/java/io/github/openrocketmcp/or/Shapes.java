package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.Transition;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Aerodynamic shape trade study: nose cone profiles (and optionally lengths) and fin edge profiles, each flown in
 * OpenRocket on a copy of the design, ranked by apogee with drag, stability and mass.
 */
public final class Shapes {
	private Shapes() {
	}

	/** A nose profile: OpenRocket shape and shape parameter (NaN = the shape has none). */
	public record Nose(String label, Transition.Shape shape, double parameter) {
	}

	public static final List<Nose> NOSES = List.of(
			new Nose("conical", Transition.Shape.CONICAL, Double.NaN),
			new Nose("tangent ogive", Transition.Shape.OGIVE, 1.0),
			new Nose("elliptical", Transition.Shape.ELLIPSOID, Double.NaN),
			new Nose("1/2 power", Transition.Shape.POWER, 0.5),
			new Nose("3/4 power", Transition.Shape.POWER, 0.75),
			new Nose("parabolic", Transition.Shape.PARABOLIC, 1.0),
			new Nose("1/2 parabola", Transition.Shape.PARABOLIC, 0.5),
			new Nose("Von Karman (Haack LD)", Transition.Shape.HAACK, 0.0),
			new Nose("LV-Haack", Transition.Shape.HAACK, 1.0 / 3));

	static void applyNose(Rocket r, String noseId, Nose n, double length) {
		NoseCone nc = (NoseCone) Components.find(r, noseId);
		nc.setShapeType(n.shape());
		if (!Double.isNaN(n.parameter())) {
			nc.setShapeParameter(n.parameter());
		}
		if (!Double.isNaN(length)) {
			nc.setLength(length);
		}
	}

	static void applyFins(Rocket r, FinSet.CrossSection cs) {
		for (RocketComponent c : r) {
			if (c instanceof FinSet f) {
				f.setCrossSection(cs);
			}
		}
	}

	record Option(String nose, String fins, Consumer<Rocket> edit) {
	}

	/** Result row for one option. */
	public record Row(String nose, String fins, double apogee, double maxMach, double minStability, double cdDesign,
			double launchMass, String error) {
	}

	public static String label(NoseCone nc) {
		for (Nose n : NOSES) {
			if (n.shape() == nc.getShapeType() && (Double.isNaN(n.parameter()) || Math.abs(n.parameter() - nc.getShapeParameter()) < 1e-6)) {
				return n.label();
			}
		}
		return nc.getShapeType().name().toLowerCase() + (nc.getShapeType().usesParameter() ? " k=" + Units.num(nc.getShapeParameter()) : "");
	}

	static String finLabel(Rocket r) {
		for (RocketComponent c : r) {
			if (c instanceof FinSet f) {
				return f.getCrossSection().name().toLowerCase();
			}
		}
		return "none";
	}

	/**
	 * Flies the current design, every nose profile (at each length if given) with the current fins, every fin edge
	 * profile with the current nose, and the best nose with the best fin profile.
	 */
	public static Map<String, Object> study(Simulation base, OpenRocketDocument doc, NoseCone nose, List<Double> noseLengths,
			boolean fins, double designMach, double minStability) {
		boolean autoMach = false;
		String noseId = nose.getID().toString();
		if (Double.isNaN(designMach)) { // compare every option's drag at the current design's peak Mach
			Variants.Run r0 = Variants.runAll(List.of(Variants.of(base, doc, null, null))).get(0);
			if (!r0.ok()) {
				throw new ToolException("Simulation failed: " + r0.error());
			}
			designMach = Math.max(0.1, r0.sim().getSimulatedData().getMaxMachNumber());
			autoMach = true;
		}
		Rocket rocket = base.getRocket();
		String curNose = label(nose), curFins = finLabel(rocket);
		List<Option> opts = new ArrayList<>();
		opts.add(new Option(curNose + " (current)", curFins + " (current)", null));
		List<Double> lengths = noseLengths == null || noseLengths.isEmpty() ? List.of(Double.NaN) : noseLengths;
		for (double len : lengths) {
			for (Nose n : NOSES) {
				boolean same = n.shape() == nose.getShapeType()
						&& (Double.isNaN(n.parameter()) || Math.abs(n.parameter() - nose.getShapeParameter()) < 1e-6)
						&& (Double.isNaN(len) || Math.abs(len - nose.getLength()) < 1e-6);
				if (same) {
					continue; // that is the current design, already the first row
				}
				String l = n.label() + (Double.isNaN(len) ? "" : ", " + Units.fmt(len, Dim.LENGTH));
				opts.add(new Option(l, curFins + " (current)", r -> applyNose(r, noseId, n, len)));
			}
		}
		boolean hasFins = !"none".equals(curFins);
		if (fins && hasFins) {
			for (FinSet.CrossSection cs : FinSet.CrossSection.values()) {
				if (cs.name().equalsIgnoreCase(curFins)) {
					continue;
				}
				opts.add(new Option(curNose + " (current)", cs.name().toLowerCase(), r -> applyFins(r, cs)));
			}
		}
		if (opts.size() > 60) {
			throw new ToolException("Too many combinations (" + opts.size() + "); give at most 5 nose lengths.");
		}
		int noseEnd = opts.size();
		for (int i = 1; i < opts.size(); i++) {
			if (!opts.get(i).fins().endsWith("(current)")) {
				noseEnd = i;
				break;
			}
		}
		List<Row> rows = evaluate(base, doc, opts, designMach);
		Row current = rows.get(0);
		double mach = designMach;

		// Best nose and best fins (feasible, max apogee), then fly them together.
		Row bestNose = best(rows.subList(0, noseEnd), minStability);
		Row bestFins = fins && hasFins ? best(rows.subList(noseEnd, rows.size()), minStability) : null;
		if (bestNose == rows.get(0) || bestFins == null) {
			bestNose = null; // the current nose is best, or there is no fin alternative: nothing to combine
		}
		if (bestNose != null && bestFins != null) {
			int ni = rows.indexOf(bestNose), fi = rows.indexOf(bestFins);
			Option no = opts.get(ni), fo = opts.get(fi);
			List<Row> combo = evaluate(base, doc, List.of(new Option(no.nose(), fo.fins(), r -> {
				no.edit().accept(r);
				fo.edit().accept(r);
			})), designMach);
			rows = new ArrayList<>(rows);
			rows.addAll(combo);
		}

		List<Map<String, Object>> table = new ArrayList<>();
		List<Row> sorted = new ArrayList<>(rows);
		sorted.sort((a, b) -> Double.compare(Double.isNaN(b.apogee()) ? -1 : b.apogee(), Double.isNaN(a.apogee()) ? -1 : a.apogee()));
		for (Row r : sorted) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("nose", r.nose());
			m.put("finEdges", r.fins());
			if (r.error() != null) {
				m.put("error", r.error());
				table.add(m);
				continue;
			}
			m.put("apogee", Units.fmt(r.apogee(), Dim.DISTANCE));
			m.put("vsCurrent", Units.num(100 * (r.apogee() - current.apogee()) / current.apogee()) + "%");
			m.put("cdAtDesignMach", Units.num(r.cdDesign()));
			m.put("minAscentStability", Units.num(r.minStability()) + " cal"
					+ (!Double.isNaN(minStability) && r.minStability() < minStability ? " (below " + Units.num(minStability) + ")" : ""));
			m.put("launchMass", Units.fmt(r.launchMass(), Dim.MASS));
			m.put("maxMach", Units.num(r.maxMach()));
			table.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("designMach", Units.num(mach) + (autoMach ? " (simulated max Mach of the current design)" : ""));
		out.put("current", curNose + " nose, " + curFins + " fin edges");
		out.put("options", table);
		Row top = best(rows, minStability);
		if (top != null) {
			double gain = 100 * (top.apogee() - current.apogee()) / current.apogee();
			String rec = top.nose() + " nose with " + top.fins() + " fin edges: " + Units.fmt(top.apogee(), Dim.DISTANCE)
					+ " (" + (gain >= 0 ? "+" : "") + Units.num(gain) + "% vs current)";
			if (Math.abs(gain) < 2) {
				rec += ". The difference is within OpenRocket's drag-model uncertainty: choose on manufacturability, "
						+ "shoulder / bay volume and cost rather than on this table.";
			}
			out.put("bestMeetingStability", rec);
		} else {
			Row any = best(rows, Double.NaN);
			out.put("bestMeetingStability", "none: no option reaches " + Units.num(minStability) + " cal minimum ascent stability "
					+ "(current " + Units.num(current.minStability()) + " cal). Fix stability first (ballast, fin area), then "
					+ "re-run." + (any == null ? "" : " Ignoring stability, the best apogee is " + any.nose() + " / " + any.fins()
					+ " at " + Units.fmt(any.apogee(), Dim.DISTANCE) + "."));
		}
		out.put("guidance", List.of(
				"Subsonic (< Mach 0.8): skin friction dominates; nose shape changes apogee by ~1-3%. Tangent ogive or "
						+ "elliptical are easy to make and near-optimal; a smooth finish and filled fin joints matter more.",
				"Transonic / supersonic: theory favours Von Karman / LV-Haack and a longer nose (5-6 calibers) for wave drag, "
						+ "and blunt shapes (elliptical, 1/2 power) lose. OpenRocket uses empirical per-shape curves, so at modest "
						+ "fineness a tangent ogive can rank equal or better in this table: trust the table for this vehicle, and "
						+ "the theory only as a tie-breaker.",
				"Fin edges: airfoiled or beveled edges reduce fin pressure drag, most at high speed; they also lower the fin's "
						+ "thickness where it is thin, so re-check fin_flutter. Swept, clipped-delta planforms suit supersonic flight.",
				"OpenRocket's nose and fin pressure-drag models are empirical and least accurate transonic; confirm close "
						+ "calls with RASAero (import_aero_table) or CFD before committing tooling."));
		return out;
	}

	static Row best(List<Row> rows, double minStability) {
		Row best = null;
		for (Row r : rows) {
			if (r.error() != null || Double.isNaN(r.apogee())) {
				continue;
			}
			if (!Double.isNaN(minStability) && r.minStability() < minStability) {
				continue;
			}
			if (best == null || r.apogee() > best.apogee()) {
				best = r;
			}
		}
		return best;
	}

	static List<Row> evaluate(Simulation base, OpenRocketDocument doc, List<Option> opts, double designMach) {
		List<Simulation> sims = new ArrayList<>();
		for (Option o : opts) {
			sims.add(Variants.of(base, doc, o.edit(), null));
		}
		List<Variants.Run> runs = Variants.runAll(sims);
		List<Row> rows = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			Variants.Run r = runs.get(i);
			Option o = opts.get(i);
			if (!r.ok()) {
				rows.add(new Row(o.nose(), o.fins(), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, r.error()));
				continue;
			}
			var data = r.sim().getSimulatedData();
			FlightConfiguration fc = r.sim().getRocket().getFlightConfiguration(r.sim().getFlightConfigurationId());
			double mach = designMach;
			Sims.Window w = Sims.ascentStability(data.getBranch(0));
			rows.add(new Row(o.nose(), o.fins(), data.getMaxAltitude(), data.getMaxMachNumber(), w == null ? Double.NaN : w.min(),
					Aero.sweep(fc, new double[] { Math.max(0.1, mach) }).get(0).cd(), MassCalculator.calculateLaunch(fc).getMass(), null));
		}
		return rows;
	}
}
