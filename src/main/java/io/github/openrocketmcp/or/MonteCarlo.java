package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Dispersion analysis: many simulations with randomized launch conditions, summarized as landing ellipses per stage,
 * apogee spread, worst-case stability and rail exit, and worst-case deployment conditions / opening loads.
 */
public final class MonteCarlo {
	private MonteCarlo() {
	}

	/** Randomization settings, SI (angles in radians). NaN mean = use the base simulation's value. */
	public record Settings(int runs, double windSpeed, double windSpeedSd, double windDirection, double windDirectionSd,
			double launchAngle, double launchAngleSd, double launchDirectionSd, double turbulence, long seed) {
	}

	static double gauss(Random r, double mean, double sd) {
		return mean + sd * r.nextGaussian();
	}

	public static Map<String, Object> run(Simulation base, OpenRocketDocument doc, Settings s, Standards std) {
		SimulationOptions bo = base.getOptions();
		double windMean = Double.isNaN(s.windSpeed()) ? bo.getWindSpeedAverage() : s.windSpeed();
		double angleMean = Double.isNaN(s.launchAngle()) ? bo.getLaunchRodAngle() : s.launchAngle();
		Random rnd = new Random(s.seed());
		List<Simulation> sims = new ArrayList<>();
		for (int i = 0; i < s.runs(); i++) {
			double wind = Math.max(0, gauss(rnd, windMean, s.windSpeedSd()));
			double dir = Double.isNaN(s.windDirection()) ? rnd.nextDouble() * 2 * Math.PI
					: gauss(rnd, s.windDirection(), s.windDirectionSd());
			double angle = Math.abs(gauss(rnd, angleMean, s.launchAngleSd()));
			double rodDir = gauss(rnd, bo.getLaunchRodDirection(), s.launchDirectionSd());
			int seed = rnd.nextInt();
			Simulation v = Variants.of(base, doc, null, null);
			SimulationOptions o = v.getOptions();
			o.setWindSpeedAverage(wind);
			o.setWindDirection(((dir % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI));
			o.setLaunchRodAngle(Math.min(angle, Math.toRadians(60)));
			o.setLaunchRodDirection(((rodDir % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI));
			if (!Double.isNaN(s.turbulence())) {
				o.setWindTurbulenceIntensity(s.turbulence());
			}
			Variants.seed(o, seed);
			// Landing spread is insensitive to the ascent step size (0.1 s changes apogee and landing by ~0.1 m on the
			// OpenRocket examples; OpenRocket also limits each step's rotation) and it is ~25% faster.
			o.setTimeStep(Math.max(o.getTimeStep(), 0.1));
			sims.add(v);
		}
		List<Variants.Run> runs = Variants.runAll(sims);

		List<Double> apogees = new ArrayList<>();
		List<Double> minStab = new ArrayList<>();
		List<Double> railExit = new ArrayList<>();
		Map<String, List<double[]>> landings = new LinkedHashMap<>();
		Map<String, double[]> worstDeploy = new LinkedHashMap<>(); // device -> {airspeed, load, run}
		Map<String, double[]> worstDescent = new LinkedHashMap<>(); // device -> {max steady descent, min}
		int failed = 0;
		for (int i = 0; i < runs.size(); i++) {
			Variants.Run r = runs.get(i);
			if (!r.ok()) {
				failed++;
				continue;
			}
			FlightData data = r.sim().getSimulatedData();
			apogees.add(data.getMaxAltitude());
			railExit.add(data.getLaunchRodVelocity());
			Sims.Window w = Sims.ascentStability(data.getBranch(0));
			if (w != null && !Double.isNaN(w.min())) {
				minStab.add(w.min());
			}
			for (FlightDataBranch b : data.getBranches()) {
				if (b.getFirstEvent(FlightEvent.Type.GROUND_HIT) == null) {
					continue;
				}
				Branch br = Branch.of(b);
				landings.computeIfAbsent(b.getName(), k -> new ArrayList<>())
						.add(new double[] { br.last(FlightDataType.TYPE_POSITION_X), br.last(FlightDataType.TYPE_POSITION_Y) });
			}
			for (Sims.Deployment d : Sims.deployments(r.sim())) {
				String key = d.branch() + ": " + d.device().getName();
				double load = Recovery.loads(d, 0, std, Double.NaN).design();
				double[] cur = worstDeploy.get(key);
				if (cur == null || d.airspeed() > cur[0]) {
					worstDeploy.put(key, new double[] { d.airspeed(), Math.max(load, cur == null ? 0 : cur[1]), d.altitudeAgl() });
				} else {
					cur[1] = Math.max(cur[1], load);
				}
				double[] desc = worstDescent.computeIfAbsent(key, k -> new double[] { 0, Double.MAX_VALUE });
				desc[0] = Math.max(desc[0], d.steadyDescentRate());
				desc[1] = Math.min(desc[1], d.steadyDescentRate());
			}
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runs", s.runs());
		out.put("failedRuns", failed);
		Map<String, Object> cond = new LinkedHashMap<>();
		cond.put("windSpeed", Units.fmt(windMean, Dim.VELOCITY) + " ± " + Units.fmt(s.windSpeedSd(), Dim.VELOCITY) + " (1 sd)");
		cond.put("windDirection", Double.isNaN(s.windDirection()) ? "uniform (any direction)"
				: Units.fmt(s.windDirection(), Dim.ANGLE) + " ± " + Units.fmt(s.windDirectionSd(), Dim.ANGLE));
		cond.put("launchAngleFromVertical", Units.fmt(angleMean, Dim.ANGLE) + " ± " + Units.fmt(s.launchAngleSd(), Dim.ANGLE));
		cond.put("launchIntoWind", bo.getLaunchIntoWind());
		out.put("conditions", cond);
		out.put("apogee", stats(apogees, Dim.DISTANCE));
		out.put("railExitVelocity", stats(railExit, Dim.VELOCITY));
		if (!minStab.isEmpty()) {
			Map<String, Object> st = new LinkedHashMap<>();
			double[] v = sorted(minStab);
			st.put("worst", Units.num(v[0]) + " cal");
			st.put("p5", Units.num(pct(v, 5)) + " cal");
			st.put("median", Units.num(pct(v, 50)) + " cal");
			double rule = std.rule("stability.minCalibers", Dim.DIMENSIONLESS);
			if (!Double.isNaN(rule)) {
				st.put("runsBelowRule", count(v, x -> x < rule) + " of " + v.length + " below " + Units.num(rule) + " cal");
			}
			out.put("minAscentStability", st);
		}
		double railRule = std.rule("railDepartureVelocity.min", Dim.VELOCITY);
		if (!Double.isNaN(railRule) && !railExit.isEmpty()) {
			out.put("railExitBelowRule", count(sorted(railExit), x -> x < railRule) + " of " + railExit.size()
					+ " below " + Units.fmt(railRule, Dim.VELOCITY));
		}
		Map<String, Object> land = new LinkedHashMap<>();
		for (Map.Entry<String, List<double[]>> e : landings.entrySet()) {
			land.put(e.getKey(), landing(e.getValue()));
		}
		out.put("landing", land);
		List<Map<String, Object>> deps = new ArrayList<>();
		for (Map.Entry<String, double[]> e : worstDeploy.entrySet()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("device", e.getKey());
			m.put("maxDeploymentAirspeed", Units.fmt(e.getValue()[0], Dim.VELOCITY));
			m.put("maxDesignLoad", Units.fmt(e.getValue()[1], Dim.FORCE));
			double[] d = worstDescent.get(e.getKey());
			m.put("steadyDescentRateRange", Units.fmt(d[1], Dim.VELOCITY) + " to " + Units.fmt(d[0], Dim.VELOCITY));
			deps.add(m);
		}
		out.put("deployments", deps);
		out.put("notes", List.of(
				"Positions: x = east, y = north of the pad. The 2-sigma ellipse holds ~86% of landings for a normal spread.",
				"Only launch conditions are randomized (wind speed/direction, launch angle and direction, turbulence seed). "
						+ "Mass, drag and thrust variation are not modeled yet.",
				"Runs use a 0.1 s ascent time step for speed; use run_simulation for the nominal-flight numbers."));
		return out;
	}

	static double[] sorted(List<Double> v) {
		double[] a = v.stream().mapToDouble(Double::doubleValue).toArray();
		Arrays.sort(a);
		return a;
	}

	static double pct(double[] sorted, double p) {
		if (sorted.length == 0) {
			return Double.NaN;
		}
		double idx = p / 100 * (sorted.length - 1);
		int lo = (int) Math.floor(idx), hi = (int) Math.ceil(idx);
		return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
	}

	static int count(double[] v, java.util.function.DoublePredicate p) {
		int n = 0;
		for (double x : v) {
			if (p.test(x)) {
				n++;
			}
		}
		return n;
	}

	static Map<String, Object> stats(List<Double> values, Dim dim) {
		Map<String, Object> m = new LinkedHashMap<>();
		double[] v = sorted(values);
		if (v.length == 0) {
			return m;
		}
		double mean = Arrays.stream(v).average().orElse(Double.NaN);
		double var = Arrays.stream(v).map(x -> (x - mean) * (x - mean)).sum() / Math.max(1, v.length - 1);
		m.put("mean", Units.fmt(mean, dim));
		m.put("sd", Units.fmt(Math.sqrt(var), dim));
		m.put("min", Units.fmt(v[0], dim));
		m.put("max", Units.fmt(v[v.length - 1], dim));
		return m;
	}

	/** Landing statistics: mean point, distances, and the 2-sigma covariance ellipse. */
	static Map<String, Object> landing(List<double[]> pts) {
		int n = pts.size();
		double mx = 0, my = 0;
		for (double[] p : pts) {
			mx += p[0];
			my += p[1];
		}
		mx /= n;
		my /= n;
		double sxx = 0, syy = 0, sxy = 0;
		List<Double> dist = new ArrayList<>();
		for (double[] p : pts) {
			double dx = p[0] - mx, dy = p[1] - my;
			sxx += dx * dx;
			syy += dy * dy;
			sxy += dx * dy;
			dist.add(Math.hypot(p[0], p[1]));
		}
		int dof = Math.max(1, n - 1);
		sxx /= dof;
		syy /= dof;
		sxy /= dof;
		// Eigen-decomposition of the 2x2 covariance matrix.
		double tr = sxx + syy, det = sxx * syy - sxy * sxy;
		double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
		double l1 = tr / 2 + disc, l2 = Math.max(0, tr / 2 - disc);
		double theta = 0.5 * Math.atan2(2 * sxy, sxx - syy); // major axis angle from +x (east), counter-clockwise
		double bearing = Math.toDegrees(Math.PI / 2 - theta);
		bearing = ((bearing % 180) + 180) % 180;
		double[] d = sorted(dist);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("landings", n);
		m.put("meanPoint", Units.fmt(mx, Dim.DISTANCE) + " east, " + Units.fmt(my, Dim.DISTANCE) + " north");
		m.put("distanceFromPad", Map.of("median", Units.fmt(pct(d, 50), Dim.DISTANCE), "p95", Units.fmt(pct(d, 95), Dim.DISTANCE),
				"max", Units.fmt(d[d.length - 1], Dim.DISTANCE)));
		m.put("ellipse2Sigma", Units.fmt(2 * Math.sqrt(l1), Dim.DISTANCE) + " x " + Units.fmt(2 * Math.sqrt(l2), Dim.DISTANCE)
				+ " (semi-axes), major axis bearing " + Units.num(bearing) + " deg from north");
		return m;
	}
}
