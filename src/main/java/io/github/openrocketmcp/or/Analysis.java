package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.masscalc.RigidBody;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Static (non-simulated) analysis: mass, CG, CP and stability margin, per stage stack.
 */
public final class Analysis {
	private Analysis() {
	}

	public record Stability(double cpX, double cgLaunchX, double cgBurnoutX, double referenceDiameter,
			double launchMass, double burnoutMass, double length, List<String> warnings) {
		public double marginCalibers() {
			return (cpX - cgLaunchX) / referenceDiameter;
		}

		public double burnoutMarginCalibers() {
			return (cpX - cgBurnoutX) / referenceDiameter;
		}
	}

	public static double maxDiameter(FlightConfiguration config) {
		double d = 0;
		for (RocketComponent c : config.getActiveInstances().keySet()) {
			if (c instanceof SymmetricComponent sc) {
				d = Math.max(d, Math.max(sc.getForeRadius(), sc.getAftRadius()) * 2);
			}
		}
		return d;
	}

	/** True when the airframe diameter changes (transitions between different diameters). */
	public static boolean hasDiameterChange(FlightConfiguration config) {
		for (RocketComponent c : config.getActiveInstances().keySet()) {
			if (c instanceof Transition t && !(c instanceof info.openrocket.core.rocketcomponent.NoseCone)
					&& t.getForeRadius() > 0 && Math.abs(t.getForeRadius() - t.getAftRadius()) > 1e-4) {
				return true;
			}
		}
		return false;
	}

	/** Barrowman CP position (m from nose tip) at the given Mach number and zero angle of attack. */
	public static double cp(FlightConfiguration config, double mach) {
		FlightConditions conditions = new FlightConditions(config);
		conditions.setMach(Math.max(0.01, mach));
		conditions.setAOA(0);
		conditions.setRollRate(0);
		Coordinate cp = new BarrowmanCalculator().getWorstCP(config, conditions, new WarningSet());
		return cp.weight > 1e-6 ? cp.x : Double.NaN;
	}

	public static Stability stability(FlightConfiguration config, double mach) {
		WarningSet warnings = new WarningSet();
		FlightConditions conditions = new FlightConditions(config);
		conditions.setMach(mach);
		conditions.setAOA(0);
		conditions.setRollRate(0);
		Coordinate cp = new BarrowmanCalculator().getWorstCP(config, conditions, warnings);
		RigidBody launch = MassCalculator.calculateLaunch(config);
		RigidBody burnout = MassCalculator.calculateBurnout(config);
		List<String> w = new ArrayList<>();
		for (Warning warning : warnings) {
			w.add(warning.toString());
		}
		return new Stability(cp.weight > 1e-6 ? cp.x : Double.NaN, launch.getCM().x, burnout.getCM().x,
				maxDiameter(config), launch.getMass(), burnout.getMass(), config.getLength(), w);
	}

	public static Map<String, Object> render(Stability s) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("length", Units.fmt(s.length(), Dim.LENGTH));
		m.put("maxDiameter", Units.fmt(s.referenceDiameter(), Dim.LENGTH));
		m.put("launchMass", Units.fmt(s.launchMass(), Dim.MASS));
		m.put("burnoutMass", Units.fmt(s.burnoutMass(), Dim.MASS));
		m.put("cgAtLaunch", Units.fmt(s.cgLaunchX(), Dim.LENGTH) + " from nose tip");
		m.put("cgAtBurnout", Units.fmt(s.cgBurnoutX(), Dim.LENGTH) + " from nose tip");
		m.put("cp", Units.fmt(s.cpX(), Dim.LENGTH) + " from nose tip");
		m.put("stabilityAtLaunch", Units.num(s.marginCalibers()) + " cal");
		m.put("stabilityAtBurnout", Units.num(s.burnoutMarginCalibers()) + " cal");
		if (!s.warnings().isEmpty()) {
			m.put("warnings", s.warnings());
		}
		return m;
	}

	/**
	 * Stability of each stack that flies on its own: the full vehicle, then the vehicle after each lower stage
	 * separates (e.g. the sustainer alone), with the upper-stage motors still full.
	 */
	public static List<Map<String, Object>> stageStacks(FlightConfiguration config, double mach) {
		List<Map<String, Object>> out = new ArrayList<>();
		List<AxialStage> stages = new ArrayList<>(config.getRocket().getStageList());
		int n = stages.size();
		for (int bottom = n - 1; bottom >= 0; bottom--) {
			if (!config.isStageActive(bottom)) {
				continue;
			}
			FlightConfiguration stack = config.clone();
			stack.clearAllStages();
			for (int i = 0; i <= bottom; i++) {
				if (config.isStageActive(i)) {
					stack._setStageActive(i, true, false);
				}
			}
			Stability s = stability(stack, mach);
			Map<String, Object> m = new LinkedHashMap<>();
			List<String> names = new ArrayList<>();
			for (int i = 0; i <= bottom; i++) {
				names.add(stages.get(i).getName());
			}
			m.put("stack", String.join(" + ", names));
			m.put("when", bottom == n - 1 ? "on the pad" : "after " + stages.get(bottom + 1).getName() + " separates");
			m.putAll(render(s));
			out.add(m);
		}
		return out;
	}

	/** Motors in the configuration, per mount. */
	public static List<Map<String, Object>> motors(FlightConfiguration config) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (MotorConfiguration mc : config.getActiveMotors()) {
			if (mc.getMotor() == null) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("mount", ((RocketComponent) mc.getMount()).getName());
			m.put("mountId", mc.getMount().getID().toString().substring(0, 8));
			m.put("stage", mc.getMount().getStage().getName());
			m.put("motor", Motors.describe(mc.getMotor()));
			m.put("count", mc.getMotorCount());
			m.put("ejectionDelay", Units.fmt(mc.getEjectionDelay(), Dim.TIME));
			m.put("ignition", mc.toIgnitionDescription());
			out.add(m);
		}
		return out;
	}
}
