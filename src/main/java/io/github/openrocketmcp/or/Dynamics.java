package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.util.Coordinate;

/**
 * Dynamic stability: the pitch damping ratio of the vehicle along the simulated ascent, and the static margin as a
 * percentage of body length (both Launch Canada 2027 edicts).
 *
 * <p>Damping ratio (Barrowman; Apogee Components "Peak of Flight" 193-197):
 * <pre>
 *   C1  = 1/2 rho V^2 A sum(CNa_i (x_i - x_cg))          corrective moment coefficient
 *   C2A = 1/2 rho V   A sum(CNa_i (x_i - x_cg)^2)        aerodynamic damping
 *   C2R = mdot (x_nozzle - x_cg)^2                        jet damping
 *   zeta = (C2A + C2R) / (2 sqrt(C1 I_L))
 * </pre>
 * CNa_i and x_i are OpenRocket's per-component normal-force slopes and CP positions (Barrowman, zero angle of attack,
 * at the instant's Mach number); I_L is the simulated longitudinal moment of inertia about the CG.
 */
public final class Dynamics {
	private Dynamics() {
	}

	/** One sample along the ascent. */
	public record Sample(double time, double mach, double dampingRatio, double marginPercentLength, double marginCalibers) {
	}

	/** Per-component normal-force slope and CP position (absolute x) at a Mach number. */
	record Aero(double[] cna, double[] x, double refArea) {
	}

	static Aero aero(FlightConfiguration fc, double mach) {
		FlightConditions cond = new FlightConditions(fc);
		cond.setMach(Math.max(0.01, mach));
		cond.setAOA(0);
		cond.setRollRate(0);
		Map<RocketComponent, AerodynamicForces> map = new BarrowmanCalculator().getForceAnalysis(fc, cond, new WarningSet());
		List<double[]> parts = new ArrayList<>();
		for (Map.Entry<RocketComponent, AerodynamicForces> e : map.entrySet()) {
			if (e.getKey() instanceof ComponentAssembly) {
				continue; // assemblies aggregate their children
			}
			Coordinate cp = e.getValue().getCP();
			if (cp.weight != 0 && !Double.isNaN(cp.x)) {
				parts.add(new double[] { cp.weight, cp.x });
			}
		}
		double[] cna = new double[parts.size()], x = new double[parts.size()];
		for (int i = 0; i < parts.size(); i++) {
			cna[i] = parts.get(i)[0];
			x[i] = parts.get(i)[1];
		}
		return new Aero(cna, x, fc.getReferenceArea());
	}

	/** Damping ratio for one flight state. */
	public static double dampingRatio(Aero a, double rho, double v, double xcg, double inertia, double mdot, double xNozzle) {
		double m1 = 0, m2 = 0;
		for (int i = 0; i < a.cna().length; i++) {
			double arm = a.x()[i] - xcg;
			m1 += a.cna()[i] * arm;
			m2 += a.cna()[i] * arm * arm;
		}
		double c1 = 0.5 * rho * v * v * a.refArea() * m1;
		double c2 = 0.5 * rho * v * a.refArea() * m2 + Math.max(0, mdot) * (xNozzle - xcg) * (xNozzle - xcg);
		if (c1 <= 0 || inertia <= 0) {
			return Double.NaN; // statically unstable: no oscillatory mode to damp
		}
		return c2 / (2 * Math.sqrt(c1 * inertia));
	}

	/**
	 * Samples the main (top-stage) branch from rail exit to apogee while airspeed exceeds {@code minAirspeed}. The
	 * configuration follows staging: lower stages are dropped after each separation event.
	 */
	public static List<Sample> ascent(Simulation sim, double minAirspeed, int maxSamples) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double rail = Sims.eventTime(b, FlightEvent.Type.LAUNCHROD);
		double apogee = Sims.eventTime(b, FlightEvent.Type.APOGEE);
		if (Double.isNaN(rail) || Double.isNaN(apogee)) {
			return List.of();
		}
		FlightConfiguration full = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		List<Double> separations = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() == FlightEvent.Type.STAGE_SEPARATION) {
				separations.add(e.getTime());
			}
		}
		double[] t = br.time;
		double[] mass = br.col(FlightDataType.TYPE_MOTOR_MASS);
		double[] cg = br.col(FlightDataType.TYPE_CG_LOCATION);
		double[] il = br.col(FlightDataType.TYPE_LONGITUDINAL_INERTIA);
		double[] rho = br.col(FlightDataType.TYPE_AIR_DENSITY);
		double[] mach = br.col(FlightDataType.TYPE_MACH_NUMBER);
		double[] stab = br.col(FlightDataType.TYPE_STABILITY);
		int i0 = br.index(rail), i1 = br.index(apogee);
		int step = Math.max(1, (i1 - i0) / Math.max(1, maxSamples));
		Map<String, Aero> cache = new HashMap<>();
		Map<Integer, FlightConfiguration> stacks = new HashMap<>();
		List<Sample> out = new ArrayList<>();
		for (int i = i0; i <= i1; i += step) {
			double v = br.airspeed(i);
			if (!(v >= minAirspeed) || Double.isNaN(cg[i]) || Double.isNaN(il[i])) {
				continue;
			}
			int dropped = 0;
			for (double s : separations) {
				if (t[i] >= s - 1e-9) { // OpenRocket records the post-separation vehicle at the separation instant
					dropped++;
				}
			}
			FlightConfiguration fc = stacks.computeIfAbsent(dropped, n -> stack(full, n));
			String key = dropped + ":" + Math.round(mach[i] * 50);
			double machNow = mach[i];
			Aero a = cache.computeIfAbsent(key, k -> aero(fc, machNow));
			double mdot = 0;
			if (i > 0 && i + 1 < t.length && t[i + 1] > t[i - 1]) {
				mdot = -(mass[i + 1] - mass[i - 1]) / (t[i + 1] - t[i - 1]);
			}
			double xNozzle = fc.getBoundingBoxAerodynamic().max.x;
			double length = fc.getLengthAerodynamic();
			double zeta = dampingRatio(a, rho[i], v, cg[i], il[i], Double.isNaN(mdot) ? 0 : mdot, xNozzle);
			double cal = stab[i];
			double pct = Double.isNaN(cal) ? Double.NaN : cal * Analysis.maxDiameter(fc) / length * 100;
			out.add(new Sample(t[i], mach[i], zeta, pct, cal));
		}
		return out;
	}

	/** Configuration with the lowest {@code dropped} active stages removed. */
	static FlightConfiguration stack(FlightConfiguration full, int dropped) {
		if (dropped == 0) {
			return full;
		}
		FlightConfiguration c = full.clone();
		List<Integer> active = new ArrayList<>();
		for (int s = 0; s < full.getStageCount(); s++) {
			if (full.isStageActive(s)) {
				active.add(s);
			}
		}
		for (int k = 0; k < dropped && !active.isEmpty(); k++) {
			c._setStageActive(active.remove(active.size() - 1), false, false);
		}
		return c;
	}

	/** Length-to-diameter ratio of the full vehicle. */
	public static double lengthToDiameter(FlightConfiguration fc) {
		return fc.getLengthAerodynamic() / Analysis.maxDiameter(fc);
	}

	/**
	 * End of the window for the %-body-length margin rule: twice the burn duration after each ignition in the main
	 * branch, i.e. max(t_ignition + 2 x burn).
	 */
	public static double marginWindowEnd(Simulation sim) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Map<RocketComponent, Double> ignition = new HashMap<>();
		double end = Double.NaN;
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() == FlightEvent.Type.IGNITION && e.getSource() != null) {
				ignition.put(e.getSource(), e.getTime());
			} else if (e.getType() == FlightEvent.Type.BURNOUT && e.getSource() != null && ignition.containsKey(e.getSource())) {
				double t0 = ignition.get(e.getSource());
				double w = t0 + 2 * (e.getTime() - t0);
				end = Double.isNaN(end) ? w : Math.max(end, w);
			}
		}
		return end;
	}
}
