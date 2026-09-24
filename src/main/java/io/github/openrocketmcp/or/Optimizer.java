package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonPrimitive;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightData;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Goal-seeking over 1-3 continuous component properties.
 *
 * <p>Parallel shrinking-box search: each round samples a batch of points (Latin hypercube) in the current box,
 * simulates them in parallel, and re-centres a smaller box on the best feasible point. Robust to the small noise in
 * simulation results, needs no gradients, and finishes in a fixed number of simulations.
 */
public final class Optimizer {
	private Optimizer() {
	}

	/** A design variable: property of a component (SI bounds). */
	public record Variable(String componentId, String componentName, String property, double min, double max) {
		Dim dim() {
			return Components.dimOf(property);
		}
	}

	public enum Objective {
		TARGET_APOGEE, MAX_APOGEE, TARGET_STABILITY, MIN_MASS
	}

	/** Constraints; NaN = unconstrained. Stability is the simulated ascent minimum/maximum (cal). */
	public record Constraints(double minStability, double maxStability, double minRailExit, double maxMach,
			double minApogee) {
	}

	/** Metrics of one evaluated design point. */
	public record Point(double[] x, double apogee, double minStability, double maxStability, double railExit,
			double maxMach, double launchMargin, double launchMass, String error) {
		boolean simulated() {
			return !Double.isNaN(apogee);
		}
	}

	public record Result(List<Variable> vars, Point best, List<Point> evaluated, double score, boolean feasible) {
	}

	static double violation(Point p, Constraints c) {
		if (p.error() != null) {
			return 1e6;
		}
		double v = 0;
		if (!Double.isNaN(c.minStability())) {
			v += Math.max(0, c.minStability() - p.minStability());
		}
		if (!Double.isNaN(c.maxStability())) {
			v += Math.max(0, p.maxStability() - c.maxStability());
		}
		if (!Double.isNaN(c.minRailExit())) {
			v += Math.max(0, (c.minRailExit() - p.railExit()) / 10);
		}
		if (!Double.isNaN(c.maxMach())) {
			v += Math.max(0, (p.maxMach() - c.maxMach()) * 10);
		}
		if (!Double.isNaN(c.minApogee())) {
			v += Math.max(0, (c.minApogee() - p.apogee()) / 100);
		}
		return Double.isNaN(v) ? 1e6 : v;
	}

	static double objective(Point p, Objective o, double target) {
		return switch (o) {
			case TARGET_APOGEE -> Math.abs(p.apogee() - target) / Math.max(1, target);
			case MAX_APOGEE -> -p.apogee();
			case TARGET_STABILITY -> Math.abs(p.launchMargin() - target);
			case MIN_MASS -> p.launchMass();
		};
	}

	/** Lexicographic: feasibility first, then objective. */
	static double score(Point p, Objective o, double target, Constraints c) {
		double v = violation(p, c);
		double obj = objective(p, o, target);
		return v > 1e-9 ? 1e9 + v * 1e6 : (Double.isNaN(obj) ? 1e9 : obj);
	}

	private static boolean needsSimulation(Objective o, Constraints c) {
		return o != Objective.TARGET_STABILITY || !Double.isNaN(c.minStability()) || !Double.isNaN(c.maxStability())
				|| !Double.isNaN(c.minRailExit()) || !Double.isNaN(c.maxMach()) || !Double.isNaN(c.minApogee());
	}

	static void applyTo(Rocket r, List<Variable> vars, double[] x) {
		for (int i = 0; i < vars.size(); i++) {
			Variable v = vars.get(i);
			Components.set(Components.find(r, v.componentId()), v.property(), new JsonPrimitive(x[i]));
		}
	}

	public static Result run(Simulation base, OpenRocketDocument doc, List<Variable> vars, Objective o, double target,
			Constraints c, int budget, long seed) {
		if (vars.isEmpty() || vars.size() > 3) {
			throw new ToolException("Give 1 to 3 variables.");
		}
		for (Variable v : vars) {
			if (!(v.max() > v.min())) {
				throw new ToolException("Variable " + v.componentName() + "." + v.property() + " needs max > min.");
			}
		}
		boolean simulate = needsSimulation(o, c);
		int n = vars.size();
		double[] lo = new double[n], hi = new double[n];
		for (int i = 0; i < n; i++) {
			lo[i] = vars.get(i).min();
			hi[i] = vars.get(i).max();
		}
		Random rnd = new Random(seed);
		int batch = Math.max(4, Variants.threads() * 2);
		List<Point> all = new ArrayList<>();
		Point best = null;
		double bestScore = Double.MAX_VALUE;
		while (all.size() < budget) {
			int m = Math.min(batch, budget - all.size());
			List<double[]> xs = new ArrayList<>();
			for (int k = 0; k < m; k++) {
				xs.add(new double[n]);
			}
			for (int i = 0; i < n; i++) {
				int[] perm = permutation(m, rnd);
				for (int k = 0; k < m; k++) {
					// 1D: evenly spaced grid (bounds included on the first round, interior afterwards).
					// nD: Latin hypercube sample of the box.
					xs.get(k)[i] = n == 1
							? (all.isEmpty() ? lo[i] + (hi[i] - lo[i]) * k / Math.max(1, m - 1) : lo[i] + (hi[i] - lo[i]) * (k + 1) / (m + 1))
							: lo[i] + (hi[i] - lo[i]) * (perm[k] + rnd.nextDouble()) / m;
				}
			}
			List<Point> pts = evaluate(base, doc, vars, xs, simulate);
			all.addAll(pts);
			for (Point p : pts) {
				double s = score(p, o, target, c);
				if (s < bestScore) {
					bestScore = s;
					best = p;
				}
			}
			if (best == null) {
				break;
			}
			if (n == 1) {
				double[] box = bracket1d(all, best, o, target, c, lo[0], hi[0], m);
				lo[0] = box[0];
				hi[0] = box[1];
			} else {
				// Shrink the box around the best point (keep within the original bounds).
				for (int i = 0; i < n; i++) {
					double half = (hi[i] - lo[i]) * 0.3;
					double center = best.x()[i];
					lo[i] = Math.max(vars.get(i).min(), center - half);
					hi[i] = Math.min(vars.get(i).max(), center + half);
				}
			}
		}
		return new Result(vars, best, all, bestScore, best != null && violation(best, c) <= 1e-9);
	}

	/** Signed metric for target objectives (value - target). */
	static double signed(Point p, Objective o, double target) {
		return switch (o) {
			case TARGET_APOGEE -> p.apogee() - target;
			case TARGET_STABILITY -> p.launchMargin() - target;
			default -> Double.NaN;
		};
	}

	/**
	 * Next 1D search interval. For target objectives, the tightest pair of evaluated neighbours around the best point
	 * whose metric brackets the target; otherwise one grid step either side of the best point.
	 */
	static double[] bracket1d(List<Point> all, Point best, Objective o, double target, Constraints c, double lo, double hi, int m) {
		List<Point> ok = new ArrayList<>();
		for (Point p : all) {
			if (p.error() == null && violation(p, c) <= 1e-9) {
				ok.add(p);
			}
		}
		ok.sort(Comparator.comparingDouble(p -> p.x()[0]));
		double bx = best.x()[0];
		if (o == Objective.TARGET_APOGEE || o == Objective.TARGET_STABILITY) {
			double[] bestPair = null;
			for (int i = 0; i + 1 < ok.size(); i++) {
				double a = signed(ok.get(i), o, target), b = signed(ok.get(i + 1), o, target);
				double xa = ok.get(i).x()[0], xb = ok.get(i + 1).x()[0];
				if (a * b <= 0 && xb > xa && (bestPair == null || xb - xa < bestPair[1] - bestPair[0])
						&& bx >= xa - 1e-12 && bx <= xb + 1e-12) {
					bestPair = new double[] { xa, xb };
				}
			}
			if (bestPair != null) {
				return bestPair;
			}
		}
		double step = (hi - lo) / (m + 1);
		return new double[] { Math.max(lo, bx - step), Math.min(hi, bx + step) };
	}

	private static int[] permutation(int m, Random rnd) {
		int[] p = new int[m];
		for (int i = 0; i < m; i++) {
			p[i] = i;
		}
		for (int i = m - 1; i > 0; i--) {
			int j = rnd.nextInt(i + 1);
			int t = p[i];
			p[i] = p[j];
			p[j] = t;
		}
		return p;
	}

	static List<Point> evaluate(Simulation base, OpenRocketDocument doc, List<Variable> vars, List<double[]> xs,
			boolean simulate) {
		List<Simulation> sims = new ArrayList<>();
		List<Point> statics = new ArrayList<>();
		for (double[] x : xs) {
			String[] err = new String[1];
			Simulation s = Variants.of(base, doc, r -> {
				try {
					applyTo(r, vars, x);
				} catch (ToolException e) {
					err[0] = e.getMessage();
				}
			}, null);
			FlightConfiguration fc = s.getRocket().getFlightConfiguration(s.getFlightConfigurationId());
			Analysis.Stability st = Analysis.stability(fc, 0.3);
			statics.add(new Point(x, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, st.marginCalibers(),
					st.launchMass(), err[0]));
			sims.add(s);
		}
		if (!simulate) {
			return statics;
		}
		List<Variants.Run> runs = Variants.runAll(sims);
		List<Point> out = new ArrayList<>();
		for (int i = 0; i < xs.size(); i++) {
			Point st = statics.get(i);
			Variants.Run run = runs.get(i);
			if (st.error() != null || !run.ok()) {
				out.add(new Point(st.x(), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, st.launchMargin(),
						st.launchMass(), st.error() != null ? st.error() : run.error()));
				continue;
			}
			FlightData data = run.sim().getSimulatedData();
			Sims.Window w = Sims.ascentStability(data.getBranch(0));
			out.add(new Point(st.x(), data.getMaxAltitude(), w == null ? Double.NaN : w.min(),
					w == null ? Double.NaN : w.max(), data.getLaunchRodVelocity(), data.getMaxMachNumber(),
					st.launchMargin(), st.launchMass(), null));
		}
		return out;
	}

	/** Evaluates a single point (e.g. the current design, for comparison). */
	public static Point evaluateOne(Simulation base, OpenRocketDocument doc, List<Variable> vars, double[] x, boolean simulate) {
		return evaluate(base, doc, vars, List.of(x), simulate).get(0);
	}

	public static Map<String, Object> render(Point p, List<Variable> vars) {
		Map<String, Object> m = new LinkedHashMap<>();
		Map<String, Object> values = new LinkedHashMap<>();
		for (int i = 0; i < vars.size(); i++) {
			Variable v = vars.get(i);
			double x = p.x()[i];
			values.put(v.componentName() + "." + v.property(), v.dim() == Dim.DIMENSIONLESS ? Units.num(x) : Units.fmt(x, v.dim()));
		}
		m.put("values", values);
		if (p.error() != null) {
			m.put("error", p.error());
			return m;
		}
		m.put("launchMargin", Units.num(p.launchMargin()) + " cal");
		m.put("launchMass", Units.fmt(p.launchMass(), Dim.MASS));
		if (p.simulated()) {
			m.put("apogee", Units.fmt(p.apogee(), Dim.DISTANCE));
			m.put("minAscentStability", Units.num(p.minStability()) + " cal");
			m.put("maxAscentStability", Units.num(p.maxStability()) + " cal");
			m.put("railExitVelocity", Units.fmt(p.railExit(), Dim.VELOCITY));
			m.put("maxMach", Units.num(p.maxMach()));
		}
		return m;
	}

	/** The best few evaluated points, best first. */
	public static List<Point> top(Result r, Objective o, double target, Constraints c, int k) {
		List<Point> sorted = new ArrayList<>(r.evaluated());
		sorted.sort(Comparator.comparingDouble(p -> score(p, o, target, c)));
		return sorted.subList(0, Math.min(k, sorted.size()));
	}
}
