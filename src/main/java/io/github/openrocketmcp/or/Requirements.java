package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Checks a simulated design against the active competition rule set and team standards.
 */
public final class Requirements {
	private Requirements() {
	}

	public enum Status {
		PASS, FAIL, WARN, INFO
	}

	public static final class Report {
		final List<Map<String, Object>> items = new ArrayList<>();

		void add(Status s, String item, String requirement, String value, String ref) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("status", s.name());
			m.put("item", item);
			m.put("requirement", requirement);
			m.put("value", value);
			if (ref != null && !ref.isBlank()) {
				m.put("ref", ref);
			}
			items.add(m);
		}

		public Map<String, Object> render(String rules) {
			Map<String, Object> out = new LinkedHashMap<>();
			int fail = 0, warn = 0;
			for (Map<String, Object> i : items) {
				if ("FAIL".equals(i.get("status"))) {
					fail++;
				} else if ("WARN".equals(i.get("status"))) {
					warn++;
				}
			}
			out.put("ruleSet", rules);
			out.put("summary", fail == 0 ? (warn == 0 ? "All checks pass." : "No failures; " + warn + " warning(s).")
					: fail + " failure(s), " + warn + " warning(s).");
			out.put("checks", items);
			return out;
		}
	}

	static double eventTime(FlightDataBranch b, FlightEvent.Type t) {
		return Sims.eventTime(b, t);
	}

	public static Report check(Simulation sim, Simulation windCase, Standards std) {
		Report r = new Report();
		FlightData data = sim.getSimulatedData();
		SimulationOptions opt = sim.getOptions();
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		boolean staged = fc.getActiveStageCount() > 1;
		FlightDataBranch main = data.getBranch(0);

		// Launch conditions used by the simulation
		double nominal = std.rule("launchAngleFromVertical.nominal", Dim.ANGLE);
		double tol = std.rule("launchAngleFromVertical.tolerance", Dim.ANGLE);
		if (!Double.isNaN(nominal)) {
			double angle = opt.getLaunchRodAngle();
			double stagedAngle = std.rule("launchAngleFromVertical.tiltInhibitedStaged", Dim.ANGLE);
			boolean ok = Math.abs(angle - nominal) <= tol + 1e-6
					|| (staged && !Double.isNaN(stagedAngle) && Math.abs(angle - stagedAngle) <= tol + 1e-6);
			r.add(ok ? Status.PASS : Status.WARN, "Simulated launch angle",
					Units.fmt(nominal, Dim.ANGLE) + " from vertical (84 deg elevation)" + (staged ? ", or 3 deg for tilt-inhibited staged flights" : ""),
					Units.fmt(angle, Dim.ANGLE), std.ruleRef("launchAngleFromVertical"));
		}
		double railRule = std.rule("railLength.value", Dim.LENGTH);
		if (!Double.isNaN(railRule) && Math.abs(opt.getLaunchRodLength() - railRule) > 0.05) {
			r.add(Status.INFO, "Simulated rail length", "LCRA rail is " + Units.fmt(railRule, Dim.LENGTH)
					+ " (use yours if you bring your own)", Units.fmt(opt.getLaunchRodLength(), Dim.LENGTH), std.ruleRef("railLength"));
		}
		if (Double.isNaN(std.q("launchSite.altitudeMsl", Dim.DISTANCE, Double.NaN))) {
			r.add(Status.WARN, "Launch site altitude", "Set launchSite.altitudeMsl in the team standards so air density is right",
					Units.fmt(opt.getLaunchAltitude(), Dim.DISTANCE) + " used by this simulation", null);
		}

		// Rail departure
		double railMin = std.rule("railDepartureVelocity.min", Dim.VELOCITY);
		double railAnalysis = std.rule("railDepartureVelocity.minWithDetailedAnalysis", Dim.VELOCITY);
		double railV = data.getLaunchRodVelocity();
		if (!Double.isNaN(railMin)) {
			Status s = railV >= railMin ? Status.PASS : !Double.isNaN(railAnalysis) && railV >= railAnalysis ? Status.WARN : Status.FAIL;
			r.add(s, "Rail departure velocity", ">= " + Units.fmt(railMin, Dim.VELOCITY)
					+ (Double.isNaN(railAnalysis) ? "" : " (>= " + Units.fmt(railAnalysis, Dim.VELOCITY) + " with detailed stability analysis)"),
					Units.fmt(railV, Dim.VELOCITY), std.ruleRef("railDepartureVelocity"));
		}

		// Thrust to weight at liftoff and per stage
		List<double[]> twr = ignitionTwr(sim, main);
		JsonElement byYear = std.rules().has("thrustToWeight") ? std.rules().getAsJsonObject("thrustToWeight").get("minByYear") : null;
		String year = std.str("competitionYear", "2026");
		double minTwr = byYear != null && byYear.getAsJsonObject().has(year) ? byYear.getAsJsonObject().get(year).getAsDouble() : Double.NaN;
		double recTwr = std.rule("thrustToWeight.recommended", Dim.DIMENSIONLESS);
		if (!twr.isEmpty()) {
			double liftoff = twr.get(0)[1];
			if (!Double.isNaN(minTwr)) {
				Status s = liftoff < minTwr ? Status.FAIL : !Double.isNaN(recTwr) && liftoff < recTwr ? Status.WARN : Status.PASS;
				r.add(s, "Thrust-to-weight at liftoff (avg thrust / liftoff weight)",
						">= " + Units.num(minTwr) + " in " + year + (Double.isNaN(recTwr) ? "" : "; design for >= " + Units.num(recTwr)),
						Units.num(liftoff), std.ruleRef("thrustToWeight"));
			}
			if (staged) {
				double boosterMin = std.rule("thrustToWeight.stagedBoosterMin", Dim.DIMENSIONLESS);
				double sustainerMin = std.rule("thrustToWeight.stagedSustainerMin", Dim.DIMENSIONLESS);
				if (!Double.isNaN(boosterMin)) {
					r.add(liftoff >= boosterMin ? Status.PASS : Status.FAIL, "Booster thrust-to-weight (staged)",
							">= " + Units.num(boosterMin), Units.num(liftoff), std.ruleRef("thrustToWeight"));
				}
				for (int i = 1; i < twr.size(); i++) {
					if (!Double.isNaN(sustainerMin)) {
						r.add(twr.get(i)[1] >= sustainerMin ? Status.PASS : Status.FAIL,
								"Upper-stage thrust-to-weight at ignition (t=" + Units.num(twr.get(i)[0]) + " s)",
								">= " + Units.num(sustainerMin), Units.num(twr.get(i)[1]), std.ruleRef("thrustToWeight"));
					}
				}
			}
		}

		// Stability
		stability(r, sim, std, "Ascent stability", fc);
		if (windCase != null) {
			stability(r, windCase, std, "Ascent stability in " + Units.fmt(windCase.getOptions().getWindSpeedAverage(), Dim.VELOCITY) + " wind", fc);
			double railW = windCase.getSimulatedData().getLaunchRodVelocity();
			r.add(Status.INFO, "Rail departure velocity in design wind", "same as above", Units.fmt(railW, Dim.VELOCITY), std.ruleRef("maxGroundWind"));
		}

		// Staging
		if (staged) {
			double frac = std.rule("staging.ignitionAltitudeFraction", Dim.DIMENSIONLESS);
			double tiltMax = std.rule("staging.tiltLimitMax", Dim.ANGLE);
			double tiltRec = std.rule("staging.tiltLimitRecommended", Dim.ANGLE);
			for (FlightEvent e : main.getEvents()) {
				if (e.getType() == FlightEvent.Type.IGNITION && e.getTime() > 0.01) {
					double alt = Sims.at(main, FlightDataType.TYPE_ALTITUDE, e.getTime());
					double tilt = Sims.tilt(main, e.getTime());
					if (!Double.isNaN(tiltMax)) {
						Status s = tilt > tiltMax ? Status.FAIL : !Double.isNaN(tiltRec) && tilt > tiltRec ? Status.WARN : Status.PASS;
						r.add(s, "Tilt at upper-stage ignition (" + e.getSource().getName() + ")",
								"tilt lockout <= " + Units.fmt(tiltMax, Dim.ANGLE) + ", should be < " + Units.fmt(tiltRec, Dim.ANGLE)
										+ "; nominal flight must be well inside the lockout",
								Units.fmt(tilt, Dim.ANGLE), std.ruleRef("staging"));
					}
					if (!Double.isNaN(frac)) {
						r.add(Status.INFO, "Air-start altitude inhibit (" + e.getSource().getName() + ")",
								"program inhibit at " + Units.num(frac * 100) + "% of the simulated ignition altitude",
								"simulated " + Units.fmt(alt, Dim.DISTANCE) + " -> inhibit below " + Units.fmt(frac * alt, Dim.DISTANCE),
								std.ruleRef("staging"));
					}
				}
			}
		}

		// Supersonic flight: OpenRocket's aerodynamics are less accurate transonic/supersonic and fin flutter is not modeled.
		double mach = data.getMaxMachNumber();
		if (mach > 0.9) {
			r.add(mach > 1 ? Status.WARN : Status.INFO, "Maximum Mach number",
					"transonic/supersonic: verify with RASAero, check fin flutter margin and fin/nose materials",
					Units.num(mach), null);
		}

		// Recovery, per branch
		earlyDeployments(r, sim);
		recovery(r, sim, std);
		return r;
	}

	private static void stability(Report r, Simulation sim, Standards std, String label, FlightConfiguration fc) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		double rail = eventTime(b, FlightEvent.Type.LAUNCHROD);
		double apogee = eventTime(b, FlightEvent.Type.APOGEE);
		if (Double.isNaN(rail) || Double.isNaN(apogee)) {
			r.add(Status.WARN, label, "stable for the entire ascent", "no rail exit/apogee in simulation", std.ruleRef("stability"));
			return;
		}
		boolean diameterChange = Analysis.hasDiameterChange(fc);
		double min = std.rule(diameterChange ? "stability.minCalibersWithDiameterChange" : "stability.minCalibers", Dim.DIMENSIONLESS);
		if (Double.isNaN(min)) {
			return;
		}
		// Ignore the last seconds before apogee, where airspeed is too low for a meaningful static margin.
		Sims.Window w = Sims.extremes(b, FlightDataType.TYPE_STABILITY, rail, apogee, 30.48);
		double aoa = Sims.at(b, FlightDataType.TYPE_AOA, w.minTime());
		String why = aoa > Math.toRadians(3)
				? "; angle of attack " + Units.fmt(aoa, Dim.ANGLE) + " there (wind / rail exit): OpenRocket's CP moves forward at high angle of attack. Zero-AoA margin: "
						+ Units.num(zeroAoaMargin(sim, b, w.minTime())) + " cal"
				: "";
		r.add(w.min() >= min ? Status.PASS : Status.FAIL, label + " (minimum, rail exit to apogee while airspeed > 100 ft/s)",
				">= " + Units.num(min) + " cal" + (diameterChange ? " (diameter change: use RASAero CP/CD overrides)" : ""),
				Units.num(w.min()) + " cal at t=" + Units.num(w.minTime()) + " s, Mach "
						+ Units.num(Sims.at(b, FlightDataType.TYPE_MACH_NUMBER, w.minTime())) + why,
				std.ruleRef("stability"));
		double concern = std.rule("stability.overStableConcern", Dim.DIMENSIONLESS);
		double over = std.rule("stability.overStable", Dim.DIMENSIONLESS);
		if (!Double.isNaN(over)) {
			Status s = w.max() >= over ? Status.WARN : !Double.isNaN(concern) && w.max() > concern ? Status.WARN : Status.PASS;
			r.add(s, label + " (maximum, over-stability)", "should stay below ~" + Units.num(concern) + " cal; >= "
					+ Units.num(over) + " cal is over-stable", Units.num(w.max()) + " cal", std.ruleRef("stability"));
		}
	}

	/** A recovery device opening before apogee (e.g. OpenRocket's default "motor ejection charge" with a short delay). */
	static void earlyDeployments(Report r, Simulation sim) {
		for (String late : lateFirstDeployments(sim)) {
			r.add(Status.FAIL, late, "the initial deployment event shall occur at or near apogee", "", "R4.2.1");
		}
		for (Sims.Deployment d : Sims.deployments(sim)) {
			if (!Double.isNaN(d.timeAfterApogee()) && d.timeAfterApogee() < -0.5) {
				r.add(Status.FAIL, d.branch() + ": " + d.device().getName() + " deploys before apogee",
						"recovery devices must not open during ascent (check the deployment event: new parachutes in "
								+ "OpenRocket default to the motor ejection charge; use set_deployment)",
						Units.num(-d.timeAfterApogee()) + " s before apogee at " + Units.fmt(d.airspeed(), io.github.openrocketmcp.units.Dim.VELOCITY),
						"R4.2.1");
			}
		}
	}

	/** First deployment of each branch more than 3 s after apogee: the vehicle is falling fast when it opens. */
	public static List<String> lateFirstDeployments(Simulation sim) {
		List<String> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Sims.Deployment d : Sims.deployments(sim)) {
			if (!seen.add(d.branch())) {
				continue;
			}
			if (!Double.isNaN(d.timeAfterApogee()) && d.timeAfterApogee() > 3) {
				out.add(d.branch() + ": first deployment (" + d.device().getName() + ") " + Units.num(d.timeAfterApogee())
						+ " s after apogee at " + Units.fmt(d.airspeed(), Dim.VELOCITY));
			}
		}
		return out;
	}

	private static void recovery(Report r, Simulation sim, Standards std) {
		double dualAbove = std.rule("dualEventAboveApogeeAgl.value", Dim.DISTANCE);
		double drMin = std.rule("drogueDescentRate.min", Dim.VELOCITY);
		double drMax = std.rule("drogueDescentRate.max", Dim.VELOCITY);
		double drRecMin = std.rule("drogueDescentRate.recommendedMin", Dim.VELOCITY);
		double drRecMax = std.rule("drogueDescentRate.recommendedMax", Dim.VELOCITY);
		double mainAltMax = std.rule("mainDeployAltitudeMaxAgl.value", Dim.DISTANCE);
		double mainMax = std.rule("mainDescentRate.max", Dim.VELOCITY);
		double mainRec = std.rule("mainDescentRate.recommended", Dim.VELOCITY);
		List<Sims.Deployment> deps = Sims.deployments(sim);
		for (FlightDataBranch b : sim.getSimulatedData().getBranches()) {
			List<Sims.Deployment> mine = new ArrayList<>();
			for (Sims.Deployment d : deps) {
				if (d.branch().equals(b.getName())) {
					mine.add(d);
				}
			}
			double apogee = b.getMaximum(FlightDataType.TYPE_ALTITUDE);
			String name = b.getName();
			if (mine.isEmpty()) {
				r.add(Status.FAIL, name + ": recovery", "every independently recovered body needs recovery", "no deployment in simulation", null);
				continue;
			}
			if (!Double.isNaN(dualAbove) && apogee > dualAbove) {
				r.add(mine.size() >= 2 ? Status.PASS : Status.FAIL, name + ": dual-event recovery",
						"required above " + Units.fmt(dualAbove, Dim.DISTANCE) + " AGL apogee",
						mine.size() + " deployment event(s), apogee " + Units.fmt(apogee, Dim.DISTANCE), std.ruleRef("dualEventAboveApogeeAgl"));
			}
			for (int i = 0; i < mine.size(); i++) {
				Sims.Deployment d = mine.get(i);
				String role = Recovery.role(mine, i);
				double v = d.steadyDescentRate();
				if (role.equals("drogue") && !Double.isNaN(drMin)) {
					Status s = v < drMin || v > drMax ? Status.FAIL
							: !Double.isNaN(drRecMin) && (v < drRecMin || v > drRecMax) ? Status.WARN : Status.PASS;
					r.add(s, name + ": drogue descent rate (" + d.device().getName() + ")",
							Units.fmt(drMin, Dim.VELOCITY) + " to " + Units.fmt(drMax, Dim.VELOCITY) + "; should be "
									+ Units.fmt(drRecMin, Dim.VELOCITY) + " to " + Units.fmt(drRecMax, Dim.VELOCITY),
							Units.fmt(v, Dim.VELOCITY), std.ruleRef("drogueDescentRate"));
				}
				if (role.startsWith("main")) {
					if (!Double.isNaN(mainAltMax) && mine.size() > 1) {
						r.add(d.altitudeAgl() <= mainAltMax + 1 ? Status.PASS : Status.FAIL,
								name + ": main deployment altitude (" + d.device().getName() + ")",
								"<= " + Units.fmt(mainAltMax, Dim.DISTANCE) + " AGL", Units.fmt(d.altitudeAgl(), Dim.DISTANCE),
								std.ruleRef("mainDeployAltitudeMaxAgl"));
					}
					if (!Double.isNaN(mainMax)) {
						Status s = v >= mainMax ? Status.FAIL : !Double.isNaN(mainRec) && v > mainRec * 1.25 ? Status.WARN : Status.PASS;
						r.add(s, name + ": landing descent rate (" + d.device().getName() + ")",
								"< " + Units.fmt(mainMax, Dim.VELOCITY) + ", ideally about " + Units.fmt(mainRec, Dim.VELOCITY),
								Units.fmt(v, Dim.VELOCITY), std.ruleRef("mainDescentRate"));
					}
				}
			}
		}
	}

	/** Static margin at zero angle of attack at time t: (Barrowman CP at that Mach - simulated CG) / reference diameter. */
	static double zeroAoaMargin(Simulation sim, FlightDataBranch b, double t) {
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		double cp = Analysis.cp(fc, Sims.at(b, FlightDataType.TYPE_MACH_NUMBER, t));
		double cg = Sims.at(b, FlightDataType.TYPE_CG_LOCATION, t);
		return (cp - cg) / Analysis.maxDiameter(fc);
	}

	/** [time, average thrust-to-weight] for each ignition in the main branch. */
	static List<double[]> ignitionTwr(Simulation sim, FlightDataBranch b) {
		List<double[]> out = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() != FlightEvent.Type.IGNITION || !(e.getSource() instanceof MotorMount mount)) {
				continue;
			}
			MotorConfiguration mc = mount.getMotorConfig(sim.getFlightConfigurationId());
			if (mc == null || !(mc.getMotor() instanceof ThrustCurveMotor m)) {
				continue;
			}
			double mass = Sims.at(b, FlightDataType.TYPE_MASS, e.getTime());
			double thrust = m.getAverageThrustEstimate() * mc.getMotorCount();
			// Several motors igniting together (clusters, parallel boosters) add up.
			if (!out.isEmpty() && Math.abs(out.get(out.size() - 1)[0] - e.getTime()) < 0.01) {
				double[] last = out.get(out.size() - 1);
				last[1] += thrust / (mass * Atmosphere.G0);
			} else {
				out.add(new double[] { e.getTime(), thrust / (mass * Atmosphere.G0) });
			}
		}
		return out;
	}
}
