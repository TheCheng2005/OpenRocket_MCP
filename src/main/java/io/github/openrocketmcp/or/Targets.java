package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * The team's own targets (standards "targets": apogee, rail exit speed, stability window, descent rates...) and the
 * margin of a simulated flight to each. Competition rules are {@link Requirements}; these are the team's design goals.
 */
public final class Targets {
	private Targets() {
	}

	/** Within this fraction of a limit a met target is reported as close (WARN). */
	public static final double CLOSE = 0.05;

	/** A flight number a target can be set on; {@code suffix} labels dimensionless values ("cal"). */
	public record Metric(String id, String label, Dim dim, String suffix, ToDoubleFunction<Flight> value) {
	}

	/** A simulated flight and the standards it was flown with, read lazily by the metrics. */
	public static final class Flight {
		final Simulation sim;
		final Standards std;
		final FlightData data;
		final FlightDataBranch main;
		private List<Sims.Deployment> deployments;
		private Double flutter;

		Flight(Simulation sim, Standards std) {
			this.sim = sim;
			this.std = std;
			this.data = sim.getSimulatedData();
			this.main = data.getBranch(0);
		}

		double event(FlightEvent.Type t) {
			return Sims.eventTime(main, t);
		}

		/** Steady descent rates under each device of the main branch, in deployment order. */
		List<Sims.Deployment> deployments() {
			if (deployments == null) {
				deployments = new ArrayList<>();
				for (Sims.Deployment d : Sims.deployments(sim)) {
					if (d.branch().equals(main.getName())) {
						deployments.add(d);
					}
				}
			}
			return deployments;
		}

		double flutter() {
			if (flutter == null) {
				double min = Double.NaN;
				for (Structures.FlutterResult r : Structures.flutter(sim, std, Map.of(), Double.NaN)) {
					if (!Double.isNaN(r.minMargin()) && (Double.isNaN(min) || r.minMargin() < min)) {
						min = r.minMargin();
					}
				}
				flutter = min;
			}
			return flutter;
		}
	}

	public static final List<Metric> METRICS = List.of(
			new Metric("apogee", "Apogee", Dim.DISTANCE, null, f -> f.data.getMaxAltitude()),
			new Metric("maxVelocity", "Top speed", Dim.VELOCITY, null, f -> f.data.getMaxVelocity()),
			new Metric("maxMach", "Top Mach number", Dim.DIMENSIONLESS, null, f -> f.data.getMaxMachNumber()),
			new Metric("maxAcceleration", "Peak acceleration", Dim.ACCELERATION, null, f -> f.data.getMaxAcceleration()),
			new Metric("railExitVelocity", "Rail exit speed", Dim.VELOCITY, null, f -> f.data.getLaunchRodVelocity()),
			new Metric("thrustToWeight", "Thrust to weight (rail)", Dim.DIMENSIONLESS, null, f -> {
				double rail = f.event(FlightEvent.Type.LAUNCHROD);
				return Double.isNaN(rail) ? Double.NaN : Sims.mean(f.main, FlightDataType.TYPE_THRUST_WEIGHT_RATIO, 0, rail);
			}),
			new Metric("minStability", "Lowest stability", Dim.DIMENSIONLESS, "cal", f -> {
				Sims.Window w = Sims.ascentStability(f.main);
				return w == null ? Double.NaN : w.min();
			}),
			new Metric("maxStability", "Highest stability", Dim.DIMENSIONLESS, "cal", f -> {
				Sims.Window w = Sims.ascentStability(f.main);
				return w == null ? Double.NaN : w.max();
			}),
			new Metric("flutterMargin", "Fin flutter margin", Dim.DIMENSIONLESS, null, Flight::flutter),
			new Metric("liftoffMass", "Lift-off mass", Dim.MASS, null, f -> Sims.at(f.main, FlightDataType.TYPE_MASS, 0)),
			new Metric("timeToApogee", "Time to apogee", Dim.TIME, null, f -> f.data.getTimeToApogee()),
			// One device: it is the main. Two or more: the first is the drogue, the last the main.
			new Metric("drogueDescentRate", "Drogue descent rate", Dim.VELOCITY, null, f -> {
				List<Sims.Deployment> d = f.deployments();
				return d.size() < 2 ? Double.NaN : d.get(0).steadyDescentRate();
			}),
			new Metric("mainDescentRate", "Main descent rate", Dim.VELOCITY, null, f -> {
				List<Sims.Deployment> d = f.deployments();
				return d.isEmpty() ? Double.NaN : d.get(d.size() - 1).steadyDescentRate();
			}),
			new Metric("groundHitVelocity", "Landing speed", Dim.VELOCITY, null, f -> f.data.getGroundHitVelocity()),
			new Metric("landingDistance", "Landing distance", Dim.DISTANCE, null, f -> {
				double gh = f.event(FlightEvent.Type.GROUND_HIT);
				return Double.isNaN(gh) ? Double.NaN : Sims.at(f.main, FlightDataType.TYPE_POSITION_XY, gh);
			}),
			new Metric("flightTime", "Flight time", Dim.TIME, null, f -> f.data.getFlightTime()));

	public static Metric metric(String id) {
		for (Metric m : METRICS) {
			if (m.id().equals(id)) {
				return m;
			}
		}
		return null;
	}

	/**
	 * Every metric of the flight with the team's target for it, if any: min, max (pass/fail) and goal (the number the
	 * team aims for). The margin is the distance to the nearer limit; a met target within {@link #CLOSE} of it is WARN.
	 */
	public static Map<String, Object> evaluate(Simulation sim, Standards std) {
		JsonObject targets = targets(std);
		for (String key : targets.keySet()) {
			if (metric(key) == null && !key.endsWith("Note")) {
				throw new ToolException("Unknown target '" + key + "'. Targets: " + String.join(", ", METRICS.stream().map(Metric::id).toList()) + ".");
			}
		}
		Flight f = new Flight(sim, std);
		List<Map<String, Object>> rows = new ArrayList<>();
		int set = 0, fail = 0, close = 0;
		for (Metric m : METRICS) {
			JsonObject t = targets.has(m.id()) && targets.get(m.id()).isJsonObject() ? targets.getAsJsonObject(m.id()) : null;
			if (t == null && m.id().equals("flutterMargin")) {
				// Flutter is the one metric that costs time; without a target the team's flutterMinMargin applies.
				t = new JsonObject();
				t.addProperty("min", std.q("structures.flutterMinMargin", Dim.DIMENSIONLESS, 1.5));
				t.addProperty("from", "structures.flutterMinMargin");
			}
			double v = m.value().applyAsDouble(f);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", m.id());
			row.put("label", m.label());
			row.put("dim", m.dim() == Dim.DIMENSIONLESS ? null : m.dim().name());
			row.put("value", Double.isFinite(v) ? text(v, m) : "not reached in this flight");
			row.put("valueSI", Double.isFinite(v) ? v : null);
			if (t != null) {
				double min = bound(t, "min", m), max = bound(t, "max", m), goal = bound(t, "goal", m);
				if (Double.isNaN(min) && Double.isNaN(max) && Double.isNaN(goal)) {
					rows.add(row);
					continue;
				}
				set++;
				put(row, "min", min, m);
				put(row, "max", max, m);
				put(row, "goal", goal, m);
				if (t.has("note") && t.get("note").isJsonPrimitive()) {
					row.put("note", t.get("note").getAsString());
				}
				if (t.has("from")) {
					row.put("from", t.get("from").getAsString());
				}
				String status = "PASS";
				if (!Double.isFinite(v)) {
					status = Double.isNaN(min) && Double.isNaN(max) ? "INFO" : "FAIL";
				} else if (!Double.isNaN(min) || !Double.isNaN(max)) {
					double toMin = Double.isNaN(min) ? Double.POSITIVE_INFINITY : v - min;
					double toMax = Double.isNaN(max) ? Double.POSITIVE_INFINITY : max - v;
					double margin = Math.min(toMin, toMax);
					double limit = toMin <= toMax ? min : max;
					boolean atMin = toMin <= toMax;
					row.put("margin", text(Math.abs(margin), m) + (atMin ? margin >= 0 ? " above the minimum" : " below the minimum"
							: margin >= 0 ? " under the maximum" : " over the maximum"));
					row.put("marginSI", margin);
					double pct = limit == 0 ? Double.NaN : 100 * margin / Math.abs(limit);
					if (!Double.isNaN(pct)) {
						row.put("marginPercent", Units.num(pct));
					}
					status = margin < 0 ? "FAIL" : !Double.isNaN(pct) && pct < 100 * CLOSE ? "WARN" : "PASS";
				} else {
					status = "INFO";
				}
				if (!Double.isNaN(goal) && Double.isFinite(v)) {
					row.put("fromGoal", (v >= goal ? "+" : "") + text(v - goal, m));
				}
				row.put("status", status);
				fail += status.equals("FAIL") ? 1 : 0;
				close += status.equals("WARN") ? 1 : 0;
			}
			rows.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("simulation", sim.getName());
		out.put("summary", set == 0 ? "No targets set: add them to the team standards (\"targets\")."
				: fail == 0 ? (close == 0 ? "All " + set + " targets met." : "All " + set + " targets met; " + close + " within "
						+ Units.num(100 * CLOSE) + "% of a limit.")
						: fail + " of " + set + " targets missed" + (close > 0 ? "; " + close + " close." : "."));
		out.put("targets", rows);
		List<String> problems = Sims.problems(sim);
		if (!problems.isEmpty()) {
			out.put("PROBLEMS", problems);
		}
		return out;
	}

	static JsonObject targets(Standards std) {
		JsonElement t = std.data().get("targets");
		return t != null && t.isJsonObject() ? t.getAsJsonObject() : new JsonObject();
	}

	/** A target bound in SI; dimensionless ones may carry a word after the number ("1.5 cal", "8 x"). */
	public static double bound(JsonObject t, String key, Metric m) {
		JsonElement e = t.get(key);
		if (e == null || e.isJsonNull() || e.isJsonPrimitive() && e.getAsString().isBlank()) {
			return Double.NaN;
		}
		try {
			if (e.getAsJsonPrimitive().isNumber()) {
				return e.getAsDouble();
			}
			String s = e.getAsString().trim();
			if (m.dim() == Dim.DIMENSIONLESS) {
				return Double.parseDouble(s.replaceFirst("\\s*[A-Za-z%]*$", ""));
			}
			return Units.toSi(s, m.dim());
		} catch (RuntimeException ex) {
			throw new ToolException("Target " + m.id() + "." + key + " = " + e + " is invalid: " + ex.getMessage());
		}
	}

	private static void put(Map<String, Object> row, String key, double v, Metric m) {
		if (!Double.isNaN(v)) {
			row.put(key, text(v, m));
			row.put(key + "SI", v);
		}
	}

	static String text(double v, Metric m) {
		if (m.dim() == Dim.DIMENSIONLESS) {
			return Units.num(v) + (m.suffix() == null ? "" : " " + m.suffix());
		}
		return Units.fmt(v, m.dim());
	}
}
