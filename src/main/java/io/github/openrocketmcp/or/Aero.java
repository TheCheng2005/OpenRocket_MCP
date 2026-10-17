package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.masscalc.RigidBody;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * OpenRocket's aerodynamic model, queried directly: drag coefficient split into friction / pressure / base, per
 * component, and CP / CNalpha / static margin as functions of Mach number.
 */
public final class Aero {
	private Aero() {
	}

	/** Totals at one Mach number (zero angle of attack, sea-level ISA Reynolds number for that Mach). */
	public record Point(double mach, double cd, double friction, double pressure, double base, double cpX, double cna,
			double marginLaunch, double marginBurnout) {
	}

	static FlightConditions conditions(FlightConfiguration fc, double mach) {
		FlightConditions c = new FlightConditions(fc);
		c.setMach(Math.max(0.01, mach));
		c.setAOA(0);
		c.setRollRate(0);
		return c;
	}

	public static Point at(FlightConfiguration fc, double mach, RigidBody launch, RigidBody burnout, double refDiameter) {
		WarningSet w = new WarningSet();
		BarrowmanCalculator calc = new BarrowmanCalculator();
		AerodynamicForces f = calc.getAerodynamicForces(fc, conditions(fc, mach), w);
		Coordinate cp = calc.getCP(fc, conditions(fc, mach), w);
		double cpX = cp.weight > 1e-9 ? cp.x : Double.NaN;
		return new Point(mach, f.getCDaxial(), f.getFrictionCD(), f.getPressureCD(), f.getBaseCD(), cpX, cp.weight,
				(cpX - launch.getCM().x) / refDiameter, (cpX - burnout.getCM().x) / refDiameter);
	}

	/** CD, CP and margins over Mach numbers. */
	public static List<Point> sweep(FlightConfiguration fc, double[] machs) {
		Analysis.settle(fc);
		RigidBody launch = MassCalculator.calculateLaunch(fc);
		RigidBody burnout = MassCalculator.calculateBurnout(fc);
		double d = Analysis.maxDiameter(fc);
		List<Point> out = new ArrayList<>();
		for (double m : machs) {
			out.add(at(fc, m, launch, burnout, d));
		}
		return out;
	}

	/**
	 * How many copies of a component fly (fins in a set, rail buttons, pods). OpenRocket's per-component drag is per
	 * copy; its total multiplies by this count.
	 */
	static int instances(FlightConfiguration fc, RocketComponent c) {
		var list = fc.getActiveInstances().get(c);
		return list == null || list.isEmpty() ? 1 : list.size();
	}

	/**
	 * Per-component drag at one Mach number (all copies of the component), sorted by share of the total, plus what
	 * OpenRocket adds for the whole rocket only (the body's friction form factor and fin thickness corrections).
	 */
	public static List<Map<String, Object>> breakdown(FlightConfiguration fc, double mach) {
		Map<RocketComponent, AerodynamicForces> map = new BarrowmanCalculator().getForceAnalysis(fc, conditions(fc, mach),
				new WarningSet());
		double sum = 0, total = Double.NaN;
		List<Object[]> rows = new ArrayList<>();
		for (Map.Entry<RocketComponent, AerodynamicForces> e : map.entrySet()) {
			AerodynamicForces f = e.getValue();
			if (e.getKey() instanceof Rocket) {
				total = nz(f.getFrictionCD()) + nz(f.getPressureCD()) + nz(f.getBaseCD());
			}
			if (e.getKey() instanceof ComponentAssembly) {
				continue;
			}
			int n = instances(fc, e.getKey());
			double fr = n * nz(f.getFrictionCD()), pr = n * nz(f.getPressureCD()), ba = n * nz(f.getBaseCD());
			double cd = fr + pr + ba;
			if (cd <= 0) {
				continue;
			}
			sum += cd;
			rows.add(new Object[] { e.getKey(), cd, new double[] { fr, pr, ba }, n });
		}
		if (Double.isNaN(total) || total <= 0) {
			total = sum;
		}
		rows.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object[] r : rows) {
			RocketComponent c = (RocketComponent) r[0];
			double[] parts = (double[]) r[2];
			int n = (int) r[3];
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("component", c.getName() + " [" + Components.shortId(c) + "]" + (n > 1 ? " (all " + n + ")" : ""));
			m.put("cd", Units.num((double) r[1]));
			m.put("share", Units.num(100 * (double) r[1] / total) + "%");
			m.put("friction", Units.num(parts[0]));
			m.put("pressure", Units.num(parts[1]));
			m.put("base", Units.num(parts[2]));
			out.add(m);
		}
		double rest = total - sum;
		if (Math.abs(rest) > 0.005 * total) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("component", "whole-rocket corrections (body friction form factor, fin thickness; not per part)");
			m.put("cd", Units.num(rest));
			m.put("share", Units.num(100 * rest / total) + "%");
			out.add(m);
		}
		return out;
	}

	/** OpenRocket's geometry and aerodynamics warnings (e.g. discontinuities, thick fins, supersonic accuracy). */
	public static List<String> warnings(FlightConfiguration fc, double mach) {
		WarningSet w = new WarningSet();
		BarrowmanCalculator calc = new BarrowmanCalculator();
		calc.getAerodynamicForces(fc, conditions(fc, mach), w);
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (!(c instanceof Rocket)) {
				calc.checkGeometry(fc, c, w);
			}
		}
		List<String> out = new ArrayList<>();
		for (Warning x : w) {
			if (!out.contains(x.toString())) {
				out.add(x.toString());
			}
		}
		return out;
	}

	/**
	 * A stack in the simulated flight (main branch, while it flies: from rail exit or the separation of the stage
	 * below it to its own separation or apogee, above 100 ft/s airspeed): its fastest point and the margin there, and
	 * its lowest margin, from OpenRocket's in-flight stability and, with an imported table, from its CP and the
	 * simulated CG. Null when the stack never flies on its own.
	 */
	public static Map<String, Object> inFlight(Simulation sim, FlightConfiguration stack, AxialStage bottom, AeroTable.Table t) {
		double[] w = AeroTable.window(sim, bottom);
		if (w == null) {
			return null;
		}
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double[] mach = br.col(FlightDataType.TYPE_MACH_NUMBER), stab = br.col(FlightDataType.TYPE_STABILITY);
		double[] cg = br.col(FlightDataType.TYPE_CG_LOCATION), alt = br.col(FlightDataType.TYPE_ALTITUDE);
		double d = Analysis.maxDiameter(stack);
		int fast = -1, low = -1;
		for (int i = Math.max(0, br.index(w[0])); i < br.size() && br.time[i] <= w[1] + 1e-9; i++) {
			if (br.time[i] < w[0] - 1e-9 || br.airspeed(i) < 30.48 || Double.isNaN(mach[i])) {
				continue;
			}
			if (fast < 0 || mach[i] > mach[fast]) {
				fast = i;
			}
			if (!Double.isNaN(stab[i]) && (low < 0 || stab[i] < stab[low])) {
				low = i;
			}
		}
		if (fast < 0) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("flies", "t=" + Units.num(w[0]) + " to " + Units.num(w[1]) + " s");
		out.put("maxMach", Units.num(mach[fast]));
		Map<String, Object> at = new LinkedHashMap<>();
		at.put("time", Units.fmt(br.time[fast], Dim.TIME));
		at.put("altitude", Units.fmt(alt[fast], Dim.DISTANCE));
		at.put("airspeed", Units.fmt(br.airspeed(fast), Dim.VELOCITY));
		at.put("cg", Units.fmt(cg[fast], Dim.LENGTH) + " from the nose tip");
		at.put("margin", Units.num(stab[fast]) + " cal (OpenRocket, in flight)");
		if (t != null && t.hasCp()) {
			at.put("cpImported", Units.fmt(t.cpAt(mach[fast]), Dim.LENGTH));
			at.put("marginImportedCp", Units.num((t.cpAt(mach[fast]) - cg[fast]) / d) + " cal (" + t.source() + ")");
		}
		out.put("atMaxMach", at);
		if (low >= 0) {
			out.put("minMargin", Units.num(stab[low]) + " cal at t=" + Units.num(br.time[low]) + " s (Mach " + Units.num(mach[low]) + ")");
		}
		if (t != null && t.hasCp()) {
			AeroTable.Margin m = AeroTable.minMargin(sim, t, d, bottom);
			if (m != null) {
				out.put("minMarginImportedCp", Units.num(m.min()) + " cal at t=" + Units.num(m.time()) + " s (Mach " + Units.num(m.mach()) + ")");
			}
		}
		return out;
	}

	/** The fastest the stack gets while it flies, or NaN. */
	public static double maxMach(Simulation sim, AxialStage bottom) {
		double[] w = AeroTable.window(sim, bottom);
		if (w == null) {
			return Double.NaN;
		}
		Branch br = Branch.of(sim.getSimulatedData().getBranch(0));
		double[] mach = br.col(FlightDataType.TYPE_MACH_NUMBER);
		double top = Double.NaN;
		for (int i = 0; i < br.size(); i++) {
			if (br.time[i] >= w[0] - 1e-9 && br.time[i] <= w[1] + 1e-9 && !(mach[i] <= top)) {
				top = Double.isNaN(mach[i]) ? top : mach[i];
			}
		}
		return top;
	}

	public static Map<String, Object> render(Point p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("mach", Units.num(p.mach()));
		m.put("cd", Units.num(p.cd()));
		m.put("friction", Units.num(p.friction()));
		m.put("pressure", Units.num(p.pressure()));
		m.put("base", Units.num(p.base()));
		m.put("cp", Units.fmt(p.cpX(), Dim.LENGTH));
		m.put("cnAlpha", Units.num(p.cna()) + " /rad");
		m.put("marginLaunch", Units.num(p.marginLaunch()) + " cal");
		m.put("marginBurnout", Units.num(p.marginBurnout()) + " cal");
		return m;
	}

	static double nz(double v) {
		return Double.isNaN(v) ? 0 : v;
	}
}
