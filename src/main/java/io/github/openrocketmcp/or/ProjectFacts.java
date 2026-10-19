package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * The numbers a team quotes about its rocket in meetings and status updates (apogee, speeds, stability, masses,
 * descent rates, flutter margin, motor), from the design and its simulation, under names people use. Used to check
 * what meeting notes and messages say against the design, and to track how the design changes week to week.
 */
public final class ProjectFacts {
	private ProjectFacts() {
	}

	/** One quantity: SI value (NaN for text facts), its dimension (null: dimensionless), text for motors, how to compare. */
	public record Fact(String key, String label, double si, Dim dim, String unitText, String text, double relTol, double absTol) {
		public String shown() {
			if (text != null) {
				return text;
			}
			return dim == null ? Units.num(si) + (unitText.isEmpty() ? "" : " " + unitText) : Units.fmt(si, dim);
		}
	}

	/** Facts in a fixed order (the order reports list them in). */
	public static Map<String, Fact> of(Simulation sim, FlightConfiguration fc, Standards std) {
		Map<String, Fact> out = new LinkedHashMap<>();
		FlightData data = sim.getSimulatedData();
		FlightDataBranch b = data.getBranch(0);
		Analysis.Stability st = Analysis.stability(fc, 0.3);
		List<String> motors = new ArrayList<>();
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() != null) {
				String m = mc.getMotor().getDesignation() + (mc.getMotorCount() > 1 ? " x" + mc.getMotorCount() : "");
				if (!motors.contains(m)) {
					motors.add(m);
				}
			}
		}
		put(out, new Fact("motor", "Motor", Double.NaN, null, "", motors.isEmpty() ? "none" : String.join(", ", motors), 0, 0));
		// The motors that light on the pad: impulse of every motor, burn time and thrust-to-weight of the first ones.
		double impulse = 0, padThrust = 0, burn = Double.NaN;
		int lowest = -1;
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() != null) {
				impulse += mc.getMotorCount() * mc.getMotor().getTotalImpulseEstimate();
				lowest = Math.max(lowest, ((info.openrocket.core.rocketcomponent.RocketComponent) mc.getMount()).getStage().getStageNumber());
			}
		}
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() != null && ((info.openrocket.core.rocketcomponent.RocketComponent) mc.getMount()).getStage().getStageNumber() == lowest
					&& mc.getIgnitionDelay() <= 0) {
				padThrust += mc.getMotorCount() * mc.getMotor().getAverageThrustEstimate();
				burn = Double.isNaN(burn) ? mc.getMotor().getBurnTimeEstimate() : Math.max(burn, mc.getMotor().getBurnTimeEstimate());
			}
		}
		if (impulse > 0) {
			put(out, num("totalImpulse", "Total impulse", impulse, Dim.IMPULSE, 0.03, 0));
		}
		put(out, num("burnTime", "Burn time (pad motors)", burn, Dim.TIME, 0.05, 0.1));
		put(out, num("apogee", "Apogee", data.getMaxAltitude(), Dim.DISTANCE, 0.03, 0));
		put(out, num("timeToApogee", "Time to apogee", data.getTimeToApogee(), Dim.TIME, 0.05, 0));
		put(out, num("maxVelocity", "Max speed", data.getMaxVelocity(), Dim.VELOCITY, 0.03, 0));
		put(out, new Fact("maxMach", "Max Mach", data.getMaxMachNumber(), null, "", null, 0, 0.03));
		put(out, num("maxAcceleration", "Max acceleration", data.getMaxAcceleration(), Dim.ACCELERATION, 0.05, 0));
		put(out, num("railExitVelocity", "Rail exit speed", data.getLaunchRodVelocity(), Dim.VELOCITY, 0.03, 0));
		Sims.Window w = Sims.ascentStability(b);
		if (w != null) {
			put(out, new Fact("minStability", "Min stability (ascent)", w.min(), null, "cal", null, 0, 0.1));
			put(out, new Fact("maxStability", "Max stability (ascent)", w.max(), null, "cal", null, 0, 0.1));
		}
		put(out, new Fact("staticMargin", "Static margin (pad, Mach 0.3)", st.marginCalibers(), null, "cal", null, 0, 0.1));
		put(out, num("liftoffMass", "Liftoff mass", st.launchMass(), Dim.MASS, 0.02, 0));
		if (padThrust > 0) {
			put(out, new Fact("thrustToWeight", "Thrust-to-weight (average, pad motors)", padThrust / (st.launchMass() * 9.80665), null, "", null,
					0.05, 0));
		}
		put(out, num("burnoutMass", "Burnout mass", st.burnoutMass(), Dim.MASS, 0.02, 0));
		put(out, num("length", "Length", fc.getLength(), Dim.LENGTH, 0.005, 0.002));
		put(out, num("diameter", "Diameter", st.referenceDiameter(), Dim.LENGTH, 0.01, 0.0005));
		put(out, num("cg", "CG at liftoff (from the nose tip)", st.cgLaunchX(), Dim.LENGTH, 0.01, 0.005));
		put(out, num("cp", "CP (from the nose tip)", st.cpX(), Dim.LENGTH, 0.01, 0.005));
		// Descent: the deploying devices of the main branch in order (two or more: the first is the drogue, the last the main).
		List<Sims.Deployment> deps = new ArrayList<>();
		for (Sims.Deployment d : Sims.deployments(sim)) {
			if (d.branch().equals(b.getName()) && !Double.isNaN(d.steadyDescentRate())) {
				deps.add(d);
			}
		}
		for (int i = 0; i < deps.size(); i++) {
			Sims.Deployment d = deps.get(i);
			String role = deps.size() == 1 ? "main" : i == 0 ? "drogue" : i == deps.size() - 1 ? "main" : null;
			if (role != null) {
				put(out, num(role + "DescentRate", d.device().getName() + " descent rate", d.steadyDescentRate(), Dim.VELOCITY, 0.08, 0));
				put(out, num(role + "DeployAltitude", d.device().getName() + " deployment altitude (AGL)", d.altitudeAgl(), Dim.DISTANCE,
						0.05, 15));
			}
		}
		put(out, num("landingDistance", "Landing distance from the pad", Branch.of(b).last(FlightDataType.TYPE_POSITION_XY), Dim.DISTANCE,
				0.15, 0));
		try {
			double worst = Double.NaN;
			for (Structures.FlutterResult f : Structures.flutter(sim, std, null, Double.NaN)) {
				if (!Double.isNaN(f.minMargin()) && !(worst <= f.minMargin())) {
					worst = f.minMargin();
				}
			}
			put(out, new Fact("flutterMargin", "Fin flutter margin (lowest, flutter speed / airspeed)", worst, null, "", null, 0, 0.05));
		} catch (ToolException e) {
			// fin material without a shear modulus: no margin to quote
		}
		return out;
	}

	private static Fact num(String key, String label, double si, Dim dim, double rel, double abs) {
		return new Fact(key, label, si, dim, "", null, rel, abs);
	}

	private static void put(Map<String, Fact> m, Fact f) {
		if (f.text() != null || !Double.isNaN(f.si())) {
			m.put(f.key(), f);
		}
	}

	/** What people call each quantity, to the fact's key (lower case, letters and digits only). */
	static final Map<String, String> ALIASES = aliases();

	private static Map<String, String> aliases() {
		Map<String, String> m = new LinkedHashMap<>();
		String[][] a = {
				{ "apogee", "apogee", "altitude", "maxaltitude", "peakaltitude", "targetaltitude" },
				{ "timeToApogee", "timetoapogee", "coasttime" },
				{ "maxVelocity", "maxvelocity", "maxspeed", "topspeed", "velocity", "speed", "peakvelocity" },
				{ "maxMach", "maxmach", "mach", "machnumber", "peakmach" },
				{ "maxAcceleration", "maxacceleration", "acceleration", "maxaccel", "maxg", "gload", "gforce" },
				{ "railExitVelocity", "railexitvelocity", "railexitspeed", "railexit", "offtherail", "railvelocity",
						"railspeed", "railexitvelocity" },
				{ "minStability", "stability", "minstability", "stabilitymargin", "minimumstability", "caliber", "calibers" },
				{ "maxStability", "maxstability", "maximumstability" },
				{ "staticMargin", "staticmargin", "padstability", "stabilityonthepad" },
				{ "liftoffMass", "liftoffmass", "mass", "weight", "wetmass", "launchmass", "grossmass", "totalmass", "liftoffweight" },
				{ "burnoutMass", "burnoutmass", "drymass", "dryweight", "emptymass" },
				{ "length", "length", "overalllength", "totallength", "height" },
				{ "diameter", "diameter", "bodydiameter", "airframediameter", "od" },
				{ "cg", "cg", "centerofgravity", "centreofgravity", "centerofmass", "centreofmass" },
				{ "cp", "cp", "centerofpressure", "centreofpressure" },
				{ "drogueDescentRate", "droguedescentrate", "droguedescent", "droguerate", "droguespeed" },
				{ "mainDescentRate", "maindescentrate", "maindescent", "mainrate", "descentrate", "landingspeed", "touchdownspeed",
						"descentspeed" },
				{ "totalImpulse", "totalimpulse", "impulse", "impulseclass" },
				{ "burnTime", "burntime", "burnduration", "burn" },
				{ "thrustToWeight", "thrusttoweight", "thrusttoweightratio", "tw", "twr" },
				{ "drogueDeployAltitude", "droguedeployaltitude", "droguedeployment", "drogueat", "drogueevent" },
				{ "mainDeployAltitude", "maindeployaltitude", "maindeployment", "mainat", "maindeploy", "mainevent", "deployaltitude" },
				{ "landingDistance", "landingdistance", "drift", "driftdistance", "landingrange", "range" },
				{ "flutterMargin", "fluttermargin", "flutter", "finfluttermargin" },
				{ "motor", "motor", "engine", "motorchoice" } };
		for (String[] row : a) {
			for (int i = 1; i < row.length; i++) {
				m.putIfAbsent(row[i], row[0]);
			}
		}
		return m;
	}

	/** The fact key for what someone called a quantity ("rail exit", "dry mass", "Mach"), or null. */
	public static String key(String quantity) {
		String k = quantity.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		if (ALIASES.containsKey(k)) {
			return ALIASES.get(k);
		}
		for (String key : ALIASES.values()) {
			if (key.equalsIgnoreCase(k)) {
				return key;
			}
		}
		return null;
	}

	public static List<String> known() {
		return new ArrayList<>(new java.util.LinkedHashSet<>(ALIASES.values()));
	}
}
