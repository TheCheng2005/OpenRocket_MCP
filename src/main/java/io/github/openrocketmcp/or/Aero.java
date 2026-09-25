package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.masscalc.RigidBody;
import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
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

	/** Per-component drag at one Mach number, sorted by share of the total. */
	public static List<Map<String, Object>> breakdown(FlightConfiguration fc, double mach) {
		Map<RocketComponent, AerodynamicForces> map = new BarrowmanCalculator().getForceAnalysis(fc, conditions(fc, mach),
				new WarningSet());
		double total = 0;
		List<Object[]> rows = new ArrayList<>();
		for (Map.Entry<RocketComponent, AerodynamicForces> e : map.entrySet()) {
			if (e.getKey() instanceof ComponentAssembly) {
				continue;
			}
			AerodynamicForces f = e.getValue();
			double cd = nz(f.getFrictionCD()) + nz(f.getPressureCD()) + nz(f.getBaseCD());
			if (cd <= 0) {
				continue;
			}
			total += cd;
			rows.add(new Object[] { e.getKey(), cd, f });
		}
		rows.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object[] r : rows) {
			RocketComponent c = (RocketComponent) r[0];
			AerodynamicForces f = (AerodynamicForces) r[2];
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("component", c.getName() + " [" + Components.shortId(c) + "]");
			m.put("cd", Units.num((double) r[1]));
			m.put("share", Units.num(100 * (double) r[1] / total) + "%");
			m.put("friction", Units.num(nz(f.getFrictionCD())));
			m.put("pressure", Units.num(nz(f.getPressureCD())));
			m.put("base", Units.num(nz(f.getBaseCD())));
			out.add(m);
		}
		return out;
	}

	/** OpenRocket's geometry and aerodynamics warnings (e.g. discontinuities, thick fins, supersonic accuracy). */
	public static List<String> warnings(FlightConfiguration fc, double mach) {
		WarningSet w = new WarningSet();
		BarrowmanCalculator calc = new BarrowmanCalculator();
		calc.getAerodynamicForces(fc, conditions(fc, mach), w);
		for (RocketComponent c : fc.getActiveComponents()) {
			calc.checkGeometry(fc, c, w);
		}
		List<String> out = new ArrayList<>();
		for (Warning x : w) {
			if (!out.contains(x.toString())) {
				out.add(x.toString());
			}
		}
		return out;
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
