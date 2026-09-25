package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.calc.Flutter;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Fin flutter along the simulated flight, and nose ballast for a stability target. */
public final class Structures {
	private Structures() {
	}

	// ------------------------------------------------------------------------------------------- flutter

	/** Flutter geometry of a fin set (root chord from the fin outline, tip chord from the planform area). */
	public static Flutter.Fin geometry(FinSet f, double thicknessOverride) {
		Coordinate[] pts = f.getFinPoints();
		double root = pts.length > 1 ? Math.abs(pts[pts.length - 1].x - pts[0].x) : 0;
		double t = Double.isNaN(thicknessOverride) ? f.getThickness() : thicknessOverride;
		return Flutter.Fin.equivalent(f.getSpan(), f.getPlanformArea(), root, t);
	}

	/** Worst point of one fin set along the flight. */
	public record FlutterResult(FinSet fin, Flutter.Fin geom, double shearModulus, String modulusSource, double minMargin,
			double time, double altitude, double airspeed, double flutterSpeed, double mach, double pressure,
			double speedOfSound, double seaLevelFlutterSpeed) {
	}

	/**
	 * Flutter margin (flutter speed / airspeed) of every fin set along the simulated flight. Fins of a lower stage are
	 * checked until that stage separates; the rest over the whole main branch.
	 */
	public static List<FlutterResult> flutter(Simulation sim, Standards std, Map<String, Double> modulusOverride,
			double thicknessOverride) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double[] t = br.time;
		double[] alt = br.col(FlightDataType.TYPE_ALTITUDE);
		double[] p = br.col(FlightDataType.TYPE_AIR_PRESSURE);
		double[] a = br.col(FlightDataType.TYPE_SPEED_OF_SOUND);
		double[] mach = br.col(FlightDataType.TYPE_MACH_NUMBER);
		double k = std.q("structures.flutterConstant", Dim.DIMENSIONLESS, Flutter.NACA_CONSTANT);
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		List<FlutterResult> out = new ArrayList<>();
		for (RocketComponent c : fc.getActiveComponents()) {
			if (!(c instanceof FinSet f)) {
				continue;
			}
			Flutter.Fin g = geometry(f, thicknessOverride);
			double gMod;
			String src;
			String id = f.getID().toString();
			Double ov = modulusOverride == null ? null : modulusOverride.getOrDefault(id, modulusOverride.get("*"));
			if (ov != null) {
				gMod = ov;
				src = "given";
			} else {
				Object[] m = std.shearModulus(f.getMaterial().getName());
				if (m == null) {
					throw new ToolException("No shear modulus for fin material '" + f.getMaterial().getName() + "' ("
							+ f.getName() + "). Pass shearModulus (e.g. \"2.9 GPa\" for G10) or add the material to "
							+ "structures.shearModulus in the team standards.");
				}
				gMod = (Double) m[0];
				src = "standards: " + m[1];
			}
			double end = t.length == 0 ? 0 : t[t.length - 1];
			for (FlightEvent e : b.getEvents()) {
				if (e.getType() == FlightEvent.Type.STAGE_SEPARATION && e.getSource() != null
						&& e.getSource().getID().equals(f.getStage().getID())) {
					end = Math.min(end, e.getTime());
				}
			}
			double best = Double.POSITIVE_INFINITY;
			int bi = -1;
			double bestVf = Double.NaN;
			for (int i = 0; i < t.length && t[i] <= end + 1e-9; i++) {
				double v = br.airspeed(i);
				if (!(v > 1) || Double.isNaN(p[i]) || Double.isNaN(a[i])) {
					continue;
				}
				double vf = Flutter.velocity(g, gMod, a[i], p[i], k);
				if (vf / v < best) {
					best = vf / v;
					bi = i;
					bestVf = vf;
				}
			}
			double sl = Flutter.velocity(g, gMod, 340.29, 101325, k);
			if (bi < 0) {
				out.add(new FlutterResult(f, g, gMod, src, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
						Double.NaN, Double.NaN, Double.NaN, sl));
			} else {
				out.add(new FlutterResult(f, g, gMod, src, best, t[bi], alt[bi], br.airspeed(bi), bestVf, mach[bi], p[bi],
						a[bi], sl));
			}
		}
		return out;
	}

	public static Map<String, Object> render(FlutterResult r, Standards std) {
		double need = std.q("structures.flutterMinMargin", Dim.DIMENSIONLESS, 1.5);
		double k = std.q("structures.flutterConstant", Dim.DIMENSIONLESS, Flutter.NACA_CONSTANT);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("finSet", r.fin().getName() + " [" + Components.shortId(r.fin()) + "]");
		m.put("material", r.fin().getMaterial().getName());
		m.put("shearModulus", Units.fmt(r.shearModulus(), Dim.PRESSURE) + " (" + r.modulusSource() + ")");
		Flutter.Fin g = r.geom();
		m.put("geometry", "span " + Units.fmt(g.span(), Dim.LENGTH) + ", root " + Units.fmt(g.rootChord(), Dim.LENGTH)
				+ ", tip " + Units.fmt(g.tipChord(), Dim.LENGTH) + " (equivalent), thickness " + Units.fmt(g.thickness(), Dim.LENGTH)
				+ "; AR " + Units.num(g.aspectRatio()) + ", taper " + Units.num(g.taper()) + ", t/c " + Units.num(g.thicknessRatio()));
		m.put("flutterSpeedSeaLevel", Units.fmt(r.seaLevelFlutterSpeed(), Dim.VELOCITY));
		if (Double.isNaN(r.minMargin())) {
			m.put("status", "INFO: no flight data for this fin set");
			return m;
		}
		Map<String, Object> w = new LinkedHashMap<>();
		w.put("time", Units.fmt(r.time(), Dim.TIME));
		w.put("altitude", Units.fmt(r.altitude(), Dim.DISTANCE));
		w.put("airspeed", Units.fmt(r.airspeed(), Dim.VELOCITY) + " (Mach " + Units.num(r.mach()) + ")");
		w.put("flutterSpeed", Units.fmt(r.flutterSpeed(), Dim.VELOCITY));
		m.put("worstPoint", w);
		m.put("minMargin", Units.num(r.minMargin()) + " (flutter speed / airspeed)");
		m.put("requiredMargin", Units.num(need) + " (structures.flutterMinMargin)");
		String status = r.minMargin() < 1 ? "FAIL: airspeed exceeds the estimated flutter speed"
				: r.minMargin() < need ? "WARN: below the team's flutter margin" : "PASS";
		m.put("status", status);
		if (r.minMargin() < need) {
			double target = need * r.airspeed();
			Map<String, Object> fix = new LinkedHashMap<>();
			fix.put("thickness", Units.fmt(Flutter.requiredThickness(g, r.shearModulus(), target, r.speedOfSound(), r.pressure(), k),
					Dim.LENGTH) + " at the same material and planform");
			fix.put("shearModulus", Units.fmt(Flutter.requiredShearModulus(g, target, r.speedOfSound(), r.pressure(), k), Dim.PRESSURE)
					+ " at the same thickness");
			fix.put("other", "lower aspect ratio (shorter span / longer root chord), more taper, tip-to-tip reinforcement; "
					+ "confirm with a stiffness test or FEA");
			m.put("toReachRequiredMargin", fix);
		}
		return m;
	}

	// ------------------------------------------------------------------------------------------- ballast

	/** Absolute axial position (m from the nose tip) of a point {@code local} m aft of a component's front. */
	static double absoluteX(RocketComponent c, double local) {
		Coordinate[] abs = c.toAbsolute(new Coordinate(local, 0, 0));
		return abs.length == 0 ? local : abs[0].x;
	}

	/** Ballast needed to move the launch CG so the static margin reaches {@code target} calibers; may be negative. */
	public static double analyticBallast(FlightConfiguration fc, double mach, double target, double xBallast) {
		Analysis.Stability s = Analysis.stability(fc, mach);
		double xt = s.cpX() - target * s.referenceDiameter();
		double m = s.launchMass();
		double xcg = s.cgLaunchX();
		if (Double.isNaN(xt) || xBallast >= xt - 1e-6) {
			return Double.NaN; // the ballast point is not ahead of the target CG: cannot reach the target from there
		}
		return m * (xcg - xt) / (xt - xBallast);
	}

	/**
	 * Adds a ballast mass component of {@code mass} centered at {@code local} m from the parent's front. When an
	 * ancestor overrides the mass (and CG) of its subcomponents, as teams do after weighing the rocket, the new mass
	 * would otherwise be ignored: the override is increased by the ballast mass and an overridden CG is moved
	 * accordingly.
	 */
	static MassComponent addBallast(RocketComponent parent, double local, double mass) {
		MassComponent mc = new MassComponent();
		mc.setName("Ballast (openrocket-mcp)");
		double len = 0.01;
		mc.setLength(len);
		mc.setComponentMass(Math.max(0, mass));
		mc.setAxialMethod(AxialMethod.TOP);
		mc.setAxialOffset(Math.max(0, local - len / 2));
		parent.addChild(mc);
		double xb = absoluteX(parent, local);
		for (RocketComponent p = parent; p != null; p = p.getParent()) {
			if (p.isMassOverridden() && (p.isSubcomponentsOverriddenMass() || p == parent)) {
				double m0 = p.getOverrideMass();
				if (p.isCGOverridden() && p.isSubcomponentsOverriddenCG()) {
					double front = absoluteX(p, 0);
					p.setOverrideCGX((m0 * p.getOverrideCGX() + mass * (xb - front)) / (m0 + mass));
				}
				p.setOverrideMass(m0 + mass);
				break;
			}
		}
		return mc;
	}

	/** Mass overrides that hide components added under {@code c}, described for the user; null if none. */
	public static String overrideNote(RocketComponent c, double mass) {
		for (RocketComponent p = c; p != null; p = p.getParent()) {
			if (p.isMassOverridden() && (p.isSubcomponentsOverriddenMass() || p == c)) {
				String cg = p.isCGOverridden() && p.isSubcomponentsOverriddenCG() ? " and its overridden CG moved toward the ballast" : "";
				return "'" + p.getName() + "' overrides the mass of its subcomponents (" + Units.fmt(p.getOverrideMass(), Dim.MASS)
						+ "), which would hide the ballast. The simulations raised that override by the ballast mass" + cg
						+ "; do the same in the design (new override " + Units.fmt(p.getOverrideMass() + mass, Dim.MASS)
						+ ") or re-weigh the vehicle with the ballast installed.";
			}
		}
		return null;
	}

	/** Result of the ballast solve. */
	public record Ballast(double mass, double x, double analyticMass, double baseMinStability, double minStability,
			double baseApogee, double apogee, double baseRail, double rail, int simulations, String note) {
	}

	/**
	 * Ballast mass at a location for a minimum ascent stability (simulated, rail exit to apogee): an analytic first
	 * guess from the static margin, refined with simulations (secant on the simulated minimum stability).
	 */
	public static Ballast ballast(Simulation base, OpenRocketDocument doc, RocketComponent parent, double local, double target) {
		FlightConfiguration fc = base.getRocket().getFlightConfiguration(base.getFlightConfigurationId());
		double x = absoluteX(parent, local);
		double m0 = analyticBallast(fc, 0.3, target, x);
		String pid = parent.getID().toString();
		if (Double.isNaN(m0)) {
			throw new ToolException("Ballast at " + Units.fmt(x, Dim.LENGTH) + " from the nose tip is not ahead of the CG "
					+ "needed for " + Units.num(target) + " cal; move it forward (e.g. into the nose cone) or add fin area.");
		}
		int sims = 1;
		Variants.Run r0 = Variants.runAll(List.of(Variants.of(base, doc, null, null))).get(0);
		if (!r0.ok()) {
			throw new ToolException("Simulation failed: " + r0.error());
		}
		double s0 = minStab(r0.sim());
		// First guess: shift the static margin by the simulated shortfall (the simulated minimum can sit below the static
		// margin, e.g. after staging or at angle of attack, so the static target alone may need no ballast at all).
		double staticNow = Analysis.stability(fc, 0.3).marginCalibers();
		double shifted = Double.isNaN(s0) ? m0 : analyticBallast(fc, 0.3, staticNow + (target - s0), x);
		double guess = Math.max(Math.max(m0, Double.isNaN(shifted) ? m0 : shifted), 0);
		Variants.Run r1 = r0;
		if (s0 < target && guess > 0) {
			double g = guess;
			r1 = Variants.runAll(List.of(Variants.of(base, doc, r -> addBallast(find(r, pid), local, g), null))).get(0);
			sims++;
			if (!r1.ok()) {
				throw new ToolException("Simulation failed: " + r1.error());
			}
		}
		double s1 = minStab(r1.sim());
		double mBest = guess, sBest = s1;
		Simulation simBest = r1.sim();
		String note = null;
		if (s0 >= target) {
			note = "Already meets the target without ballast (minimum ascent stability " + Units.num(s0) + " cal).";
			mBest = 0;
			sBest = s0;
			simBest = r0.sim();
		} else {
			// Root-find on the simulated minimum stability. It rises with ballast mass but is not smooth (a minimum over
			// sampled times, with turbulence), so bracket the target and use false position with a bisection fallback;
			// outside a bracket, secant steps (expanding when they stall).
			double lo = 0, sLo = s0, hi = Double.NaN, sHi = Double.NaN;
			if (s1 >= target) {
				hi = guess;
				sHi = s1;
			} else {
				lo = guess;
				sLo = s1;
			}
			double ma = 0, sa = s0, mb = guess, sb = s1;
			for (int it = 0; it < 7 && Math.abs(sb - target) > 0.02; it++) {
				double mn;
				if (!Double.isNaN(hi)) {
					mn = lo + (target - sLo) * (hi - lo) / (sHi - sLo);
					double w = hi - lo;
					if (!(mn > lo + 0.1 * w && mn < hi - 0.1 * w)) {
						mn = 0.5 * (lo + hi); // false position stalling at an end: bisect
					}
					if (w < 1e-3) {
						break; // bracket narrower than a gram
					}
				} else {
					mn = sb > sa + 1e-6 ? mb + (target - sb) * (mb - ma) / (sb - sa) : 2 * mb + 0.02;
					mn = Math.max(mn, 1.2 * mb + 0.005); // below target: must add mass
					mn = Math.min(mn, 4 * mb + 0.05); // guard against runaway steps
				}
				double mm = mn;
				Variants.Run rn = Variants.runAll(List.of(Variants.of(base, doc, r -> addBallast(find(r, pid), local, mm), null))).get(0);
				sims++;
				if (!rn.ok()) {
					break;
				}
				double sn = minStab(rn.sim());
				if (sn >= target) {
					if (Double.isNaN(hi) || mn < hi) {
						hi = mn;
						sHi = sn;
					}
				} else if (mn > lo) {
					lo = mn;
					sLo = sn;
				}
				ma = mb;
				sa = sb;
				mb = mn;
				sb = sn;
				// Keep the lightest mass that meets the target (within tolerance); otherwise the closest.
				boolean meets = sn >= target - 0.02, bestMeets = sBest >= target - 0.02;
				if ((meets && (!bestMeets || mn < mBest)) || (!meets && !bestMeets && Math.abs(sn - target) < Math.abs(sBest - target))) {
					mBest = mn;
					sBest = sn;
					simBest = rn.sim();
				}
			}
			if (sBest < target - 0.02) {
				note = "Could not reach " + Units.num(target) + " cal within the iteration budget; best "
						+ Units.num(sBest) + " cal. The minimum may occur where ballast has little effect (e.g. high AoA "
						+ "in wind or transonic CP shift); consider fin area instead.";
			}
		}
		String ov = overrideNote(parent, mBest);
		if (ov != null) {
			note = note == null ? ov : note + " " + ov;
		}
		return new Ballast(mBest, x, m0, s0, sBest, r0.sim().getSimulatedData().getMaxAltitude(),
				simBest.getSimulatedData().getMaxAltitude(), r0.sim().getSimulatedData().getLaunchRodVelocity(),
				simBest.getSimulatedData().getLaunchRodVelocity(), sims, note);
	}

	private static RocketComponent find(Rocket r, String id) {
		return Components.find(r, id);
	}

	static double minStab(Simulation s) {
		Sims.Window w = Sims.ascentStability(s.getSimulatedData().getBranch(0));
		return w == null ? Double.NaN : w.min();
	}

	/** Launch mass of the active configuration (for reporting ballast as a fraction). */
	public static double launchMass(FlightConfiguration fc) {
		return MassCalculator.calculateLaunch(fc).getMass();
	}
}
