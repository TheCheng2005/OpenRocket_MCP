package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;
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

	/**
	 * Randomization settings, SI (angles in radians). NaN mean = use the base simulation's value. The vehicle
	 * variations are fractional standard deviations (0.05 = 5%) applied as multipliers: structure mass (motors
	 * excluded; each component scaled, so the dry CG stays put), airframe drag coefficient, motor thrust (total impulse
	 * at the same burn time), and each parachute's Cd independently.
	 */
	public record Settings(int runs, double windSpeed, double windSpeedSd, double windDirection, double windDirectionSd,
			double launchAngle, double launchAngleSd, double launchDirectionSd, double turbulence, long seed,
			double massSd, double dragSd, double thrustSd, double chuteCdSd) {
		public Settings(int runs, double windSpeed, double windSpeedSd, double windDirection, double windDirectionSd,
				double launchAngle, double launchAngleSd, double launchDirectionSd, double turbulence, long seed) {
			this(runs, windSpeed, windSpeedSd, windDirection, windDirectionSd, launchAngle, launchAngleSd, launchDirectionSd,
					turbulence, seed, 0, 0, 0, 0);
		}

		boolean varyVehicle() {
			return massSd > 0 || dragSd > 0 || thrustSd > 0 || chuteCdSd > 0;
		}
	}

	/** Scales airframe drag and motor thrust in one simulation. */
	static final class Scaling extends AbstractSimulationListener {
		final double drag, thrust;

		Scaling(double drag, double thrust) {
			this.drag = drag;
			this.thrust = thrust;
		}

		@Override
		public AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces) {
			if (drag == 1 || forces == null) {
				return null;
			}
			forces.setCD(forces.getCD() * drag);
			forces.setCDaxial(forces.getCDaxial() * drag);
			return forces;
		}

		@Override
		public double postSimpleThrustCalculation(SimulationStatus status, double t) {
			return thrust == 1 ? Double.NaN : t * thrust;
		}

		@Override
		public boolean isSystemListener() {
			return true;
		}
	}

	/** Multiplies every component's own mass by {@code f} (motors excluded; overridden subtrees scaled once). */
	static void scaleMass(Rocket r, double f) {
		for (RocketComponent c : r) {
			if (overriddenByAncestor(c)) {
				continue;
			}
			if (c.isMassOverridden()) {
				c.setOverrideMass(c.getOverrideMass() * f);
			} else if (!(c instanceof Rocket)) {
				double m = c.getComponentMass();
				if (m > 0) {
					c.setMassOverridden(true);
					c.setOverrideMass(m * f);
				}
			}
		}
	}

	private static boolean overriddenByAncestor(RocketComponent c) {
		for (RocketComponent p = c.getParent(); p != null; p = p.getParent()) {
			if (p.isMassOverridden() && p.isSubcomponentsOverriddenMass()) {
				return true;
			}
		}
		return false;
	}

	static void scaleChutes(Rocket r, Random rnd, double sd, List<Double> factors) {
		for (RocketComponent c : r) {
			if (c instanceof Parachute p) {
				double f = Math.max(0.3, gauss(rnd, 1, sd));
				p.setCD(p.getCD() * f);
				factors.add(f);
			}
		}
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
		List<double[]> inputs = new ArrayList<>(); // per run: wind, launch angle, mass, drag, thrust, mean chute Cd factor
		for (int i = 0; i < s.runs(); i++) {
			double wind = Math.max(0, gauss(rnd, windMean, s.windSpeedSd()));
			double dir = Double.isNaN(s.windDirection()) ? rnd.nextDouble() * 2 * Math.PI
					: gauss(rnd, s.windDirection(), s.windDirectionSd());
			double angle = Math.abs(gauss(rnd, angleMean, s.launchAngleSd()));
			double rodDir = gauss(rnd, bo.getLaunchRodDirection(), s.launchDirectionSd());
			int seed = rnd.nextInt();
			double fm = s.massSd() > 0 ? Math.max(0.5, gauss(rnd, 1, s.massSd())) : 1;
			double fd = s.dragSd() > 0 ? Math.max(0.3, gauss(rnd, 1, s.dragSd())) : 1;
			double ft = s.thrustSd() > 0 ? Math.max(0.5, gauss(rnd, 1, s.thrustSd())) : 1;
			long chuteSeed = rnd.nextLong();
			List<Double> chuteF = new ArrayList<>();
			Simulation v = Variants.of(base, doc, s.massSd() > 0 || s.chuteCdSd() > 0 ? r -> {
				if (fm != 1) {
					scaleMass(r, fm);
				}
				if (s.chuteCdSd() > 0) {
					scaleChutes(r, new Random(chuteSeed), s.chuteCdSd(), chuteF);
				}
			} : null, null);
			if (fd != 1 || ft != 1) {
				Variants.listen(v, new Scaling(fd, ft));
			}
			inputs.add(new double[] { wind, Math.min(angle, Math.toRadians(60)), fm, fd, ft,
					chuteF.isEmpty() ? 1 : chuteF.stream().mapToDouble(Double::doubleValue).average().orElse(1) });
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
		List<double[]> okInputs = new ArrayList<>();
		List<double[]> okOutputs = new ArrayList<>(); // apogee, min stability, max landing distance
		for (int i = 0; i < runs.size(); i++) {
			Variants.Run r = runs.get(i);
			if (!r.ok()) {
				failed++;
				continue;
			}
			FlightData data = r.sim().getSimulatedData();
			apogees.add(data.getMaxAltitude());
			double maxLand = 0;
			for (FlightDataBranch lb : data.getBranches()) {
				Branch lbr = Branch.of(lb);
				double dist = Math.hypot(lbr.last(FlightDataType.TYPE_POSITION_X), lbr.last(FlightDataType.TYPE_POSITION_Y));
				if (!Double.isNaN(dist)) {
					maxLand = Math.max(maxLand, dist);
				}
			}
			Sims.Window sw = Sims.ascentStability(data.getBranch(0));
			okInputs.add(inputs.get(i));
			okOutputs.add(new double[] { data.getMaxAltitude(), sw == null ? Double.NaN : sw.min(), maxLand });
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
		if (s.massSd() > 0) {
			cond.put("structureMass", "x 1 ± " + Units.num(s.massSd() * 100) + "% (motors excluded)");
		}
		if (s.dragSd() > 0) {
			cond.put("airframeDrag", "x 1 ± " + Units.num(s.dragSd() * 100) + "%");
		}
		if (s.thrustSd() > 0) {
			cond.put("motorThrust", "x 1 ± " + Units.num(s.thrustSd() * 100) + "% (same burn time)");
		}
		if (s.chuteCdSd() > 0) {
			cond.put("parachuteCd", "x 1 ± " + Units.num(s.chuteCdSd() * 100) + "% (each chute independently)");
		}
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
		Map<String, Object> drivers = drivers(okInputs, okOutputs, s);
		if (!drivers.isEmpty()) {
			out.put("drivers", drivers);
		}
		out.put("notes", List.of(
				"Positions: x = east, y = north of the pad. The 2-sigma ellipse holds ~86% of landings for a normal spread.",
				s.varyVehicle() ? "Launch conditions and vehicle properties are randomized as listed under conditions."
						: "Only launch conditions are randomized; pass massSd, dragSd, thrustSd and chuteCdSd (e.g. 0.05 = 5%) "
								+ "to include vehicle uncertainty.",
				"drivers: correlation (r) of each randomized input with the result; |r| near 1 = that input dominates the spread.",
				"Runs use a 0.1 s ascent time step for speed; use run_simulation for the nominal-flight numbers."));
		return out;
	}

	private static final String[] INPUT_NAMES = { "windSpeed", "launchAngle", "structureMass", "airframeDrag",
			"motorThrust", "parachuteCd" };

	/** Pearson correlation of each randomized input with apogee, minimum stability and farthest landing. */
	static Map<String, Object> drivers(List<double[]> in, List<double[]> out, Settings s) {
		Map<String, Object> m = new LinkedHashMap<>();
		if (in.size() < 5) {
			return m;
		}
		String[] outs = { "apogee", "minAscentStability", "farthestLanding" };
		for (int o = 0; o < outs.length; o++) {
			Map<String, Object> row = new LinkedHashMap<>();
			for (int k = 0; k < INPUT_NAMES.length; k++) {
				double r = correlation(in, out, k, o);
				if (!Double.isNaN(r)) {
					row.put(INPUT_NAMES[k], Units.num(r));
				}
			}
			if (!row.isEmpty()) {
				m.put(outs[o], row);
			}
		}
		return m;
	}

	static double correlation(List<double[]> in, List<double[]> out, int k, int o) {
		double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
		int n = 0;
		for (int i = 0; i < in.size(); i++) {
			double x = in.get(i)[k], y = out.get(i)[o];
			if (Double.isNaN(x) || Double.isNaN(y)) {
				continue;
			}
			n++;
			sx += x;
			sy += y;
			sxx += x * x;
			syy += y * y;
			sxy += x * y;
		}
		if (n < 5) {
			return Double.NaN;
		}
		double cov = sxy - sx * sy / n, vx = sxx - sx * sx / n, vy = syy - sy * sy / n;
		if (vx <= 1e-12 * Math.max(1, sxx) || vy <= 1e-12 * Math.max(1, syy)) {
			return Double.NaN; // input not varied (or output constant)
		}
		return cov / Math.sqrt(vx * vy);
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
