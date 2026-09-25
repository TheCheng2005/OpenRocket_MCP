package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.masscalc.CMAnalysisEntry;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Quasi-static rigid-body flight loads at the airframe joints.
 *
 * <pre>
 * Axial force at station x (compression +):   N(x) = m_fwd / m * (T - D) + D_fwd
 * Bending moment (normal-force case):          M(x) = sum_fwd N_i (x - x_i) - sum_fwd m_j a_j (x - x_j)
 *   N_i = q A CNa_i alpha at each component's CP, a_j = a_n + thetaDD (x_cg - x_j),
 *   a_n = sum N_i / m, thetaDD = sum N_i (x_cg - x_i) / I   (inertial relief)
 * Wall stress in a thin tube: sigma = N / (2 pi r t) + M / (pi r^2 t)
 * </pre>
 * Masses and CGs come from OpenRocket's per-component mass analysis (motor mass follows the simulation), drag split
 * and normal-force slopes from its Barrowman model at the instant's Mach number.
 */
public final class Loads {
	private Loads() {
	}

	/** A point mass (m, x from the nose tip). */
	record Point(double x, double m) {
	}

	/**
	 * Mass and CG (x) of every component as OpenRocket's mass analysis computes them (all instances), with components
	 * under a subcomponent mass override (a weighed section) scaled so the section totals the override. Motors (launch
	 * mass) are returned in {@code motorOut} = {mass, x}.
	 */
	static Map<RocketComponent, double[]> componentMasses(FlightConfiguration fc, double[] motorOut) {
		Map<Integer, CMAnalysisEntry> map = MassCalculator.getCMAnalysis(fc);
		Map<RocketComponent, double[]> out = new LinkedHashMap<>();
		double mm = 0, mmx = 0;
		for (CMAnalysisEntry e : map.values()) {
			if (e.source instanceof Motor) {
				mm += e.totalCM.weight;
				mmx += e.totalCM.weight * e.totalCM.x;
			} else if (e.source instanceof RocketComponent c && !(c instanceof ComponentAssembly) && e.totalCM.weight > 0) {
				out.put(c, new double[] { e.totalCM.weight, e.totalCM.x });
			}
		}
		Map<RocketComponent, double[]> groups = new LinkedHashMap<>();
		for (RocketComponent c : out.keySet()) {
			RocketComponent o = overrider(c);
			if (o != null) {
				groups.computeIfAbsent(o, k -> new double[1])[0] += out.get(c)[0];
			}
		}
		for (Map.Entry<RocketComponent, double[]> e : out.entrySet()) {
			RocketComponent o = overrider(e.getKey());
			if (o != null && groups.get(o)[0] > 0) {
				e.getValue()[0] *= o.getOverrideMass() / groups.get(o)[0];
			}
		}
		if (motorOut != null) {
			motorOut[0] = mm;
			motorOut[1] = mm > 0 ? mmx / mm : Double.NaN;
		}
		return out;
	}

	/** Structure point masses; the motor point (launch mass, x) goes to {@code motorOut}. */
	static List<Point> structure(FlightConfiguration fc, double[] motorOut) {
		List<Point> out = new ArrayList<>();
		for (double[] v : componentMasses(fc, motorOut).values()) {
			out.add(new Point(v[1], v[0]));
		}
		return out;
	}

	static RocketComponent overrider(RocketComponent c) {
		RocketComponent found = null;
		for (RocketComponent p = c; p != null; p = p.getParent()) {
			if (p.isMassOverridden() && p.isSubcomponentsOverriddenMass() && p != c) {
				found = p; // outermost wins
			}
		}
		return found;
	}

	/** Joints: boundaries between consecutive airframe pieces of the full stack (stage interfaces included). */
	public record Joint(String name, double x, double radius, double thickness) {
	}

	public static List<Joint> joints(FlightConfiguration fc) {
		List<RocketComponent> ps = new ArrayList<>();
		for (AxialStage st : fc.getActiveStages()) {
			ps.addAll(Sections.pieces(st));
		}
		ps.sort((a, b) -> Double.compare(Structures.absoluteX(a, 0), Structures.absoluteX(b, 0)));
		List<Joint> out = new ArrayList<>();
		for (int i = 1; i < ps.size(); i++) {
			RocketComponent prev = ps.get(i - 1), next = ps.get(i);
			double x = Structures.absoluteX(next, 0);
			// Wall of the tube carrying the joint: the thinner of the two neighbours' walls, at the joint radius.
			double r = Math.min(aftRadius(prev), foreRadius(next));
			double t = Math.min(wall(prev), wall(next));
			out.add(new Joint(prev.getName() + " / " + next.getName(), x, r, t));
		}
		return out;
	}

	static double foreRadius(RocketComponent c) {
		return c instanceof SymmetricComponent s ? s.getForeRadius() : Double.NaN;
	}

	static double aftRadius(RocketComponent c) {
		return c instanceof SymmetricComponent s ? s.getAftRadius() : Double.NaN;
	}

	static double wall(RocketComponent c) {
		if (c instanceof BodyTube bt) {
			return bt.getThickness();
		}
		return c instanceof SymmetricComponent s ? s.getThickness() : Double.NaN;
	}

	/** Per-component axial drag (N per unit q) and normal-force slope with CP, at a Mach number. */
	record Aero(double[] xDrag, double[] cdA, double[] xCp, double[] cnaA) {
	}

	static Aero aero(FlightConfiguration fc, double mach) {
		FlightConditions c = new FlightConditions(fc);
		c.setMach(Math.max(0.05, mach));
		c.setAOA(0);
		c.setRollRate(0);
		double aRef = fc.getReferenceArea();
		Map<RocketComponent, AerodynamicForces> map = new BarrowmanCalculator().getForceAnalysis(fc, c, new WarningSet());
		List<double[]> drag = new ArrayList<>(), norm = new ArrayList<>();
		for (Map.Entry<RocketComponent, AerodynamicForces> e : map.entrySet()) {
			if (e.getKey() instanceof ComponentAssembly) {
				continue;
			}
			AerodynamicForces f = e.getValue();
			double cd = nz(f.getFrictionCD()) + nz(f.getPressureCD()) + nz(f.getBaseCD());
			double x0 = Structures.absoluteX(e.getKey(), 0);
			if (cd > 0) {
				// Pressure drag acts at the front of a nose / transition, base drag at the aft end; spread the rest.
				drag.add(new double[] { x0 + 0.5 * e.getKey().getLength(), cd * aRef });
			}
			if (f.getCP() != null && f.getCP().weight != 0 && !Double.isNaN(f.getCP().x)) {
				norm.add(new double[] { f.getCP().x, f.getCP().weight * aRef });
			}
		}
		return new Aero(col(drag, 0), col(drag, 1), col(norm, 0), col(norm, 1));
	}

	private static double[] col(List<double[]> l, int i) {
		return l.stream().mapToDouble(v -> v[i]).toArray();
	}

	private static double nz(double v) {
		return Double.isNaN(v) ? 0 : v;
	}

	/** Load at one joint for one case. */
	public record Load(double axial, double moment, double time, double mach, double q) {
	}

	/** Axial force (compression +) at station x. */
	static double axial(List<Point> pts, double motorMass, double motorX, double thrust, double dragTotal, Aero a, double q,
			double x) {
		double m = motorMass, mf = motorX < x ? motorMass : 0;
		for (Point p : pts) {
			m += p.m();
			if (p.x() < x) {
				mf += p.m();
			}
		}
		double dScale;
		double cdaSum = 0, cdaFwd = 0;
		for (int i = 0; i < a.cdA().length; i++) {
			cdaSum += a.cdA()[i];
			if (a.xDrag()[i] < x) {
				cdaFwd += a.cdA()[i];
			}
		}
		dScale = cdaSum > 0 ? dragTotal / cdaSum : q; // use the simulation's total drag, OpenRocket's split
		return mf / m * (thrust - dragTotal) + cdaFwd * dScale;
	}

	/** Bending moment at station x for angle of attack alpha, with inertial relief. */
	static double moment(List<Point> pts, double motorMass, double motorX, Aero a, double q, double alpha, double x) {
		List<Point> all = new ArrayList<>(pts);
		if (motorMass > 0) {
			all.add(new Point(motorX, motorMass));
		}
		double m = 0, mx = 0;
		for (Point p : all) {
			m += p.m();
			mx += p.m() * p.x();
		}
		double xcg = mx / m, inertia = 0;
		for (Point p : all) {
			inertia += p.m() * (p.x() - xcg) * (p.x() - xcg);
		}
		double nTot = 0, mCg = 0;
		double[] n = new double[a.cnaA().length];
		for (int i = 0; i < n.length; i++) {
			n[i] = q * a.cnaA()[i] * alpha;
			nTot += n[i];
			mCg += n[i] * (xcg - a.xCp()[i]);
		}
		double an = nTot / m, th = inertia > 0 ? mCg / inertia : 0;
		double mom = 0;
		for (int i = 0; i < n.length; i++) {
			if (a.xCp()[i] < x) {
				mom += n[i] * (x - a.xCp()[i]);
			}
		}
		for (Point p : all) {
			if (p.x() < x) {
				mom -= p.m() * (an + th * (xcg - p.x())) * (x - p.x());
			}
		}
		return mom;
	}

	/** Worst loads found for one joint over all phases. */
	static final class JointLoads {
		final Joint joint;
		Load axial, gust, sim;
		double gustAlpha, simAlpha, gustAxial, stress = -1;

		JointLoads(Joint j) {
			joint = j;
		}
	}

	/**
	 * Loads at every joint, per stack phase (the full vehicle until the first separation, then each remaining stack):
	 * maximum axial compression, and bending at the phase's maximum q with a crosswind gust and at the largest
	 * simulated q x sin(AoA). The worst value per joint over all phases is reported.
	 */
	public static Map<String, Object> analyze(Simulation sim, double gustSpeed, double safetyFactor, double allowable) {
		FlightConfiguration full = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double apogee = Sims.eventTime(b, FlightEvent.Type.APOGEE);
		List<Double> seps = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() == FlightEvent.Type.STAGE_SEPARATION) {
				seps.add(e.getTime());
			}
		}
		double[] t = br.time, thrust = br.col(FlightDataType.TYPE_THRUST_FORCE), drag = br.col(FlightDataType.TYPE_DRAG_FORCE),
				mass = br.col(FlightDataType.TYPE_MASS), mach = br.col(FlightDataType.TYPE_MACH_NUMBER),
				rho = br.col(FlightDataType.TYPE_AIR_DENSITY), aoa = br.col(FlightDataType.TYPE_AOA);
		Map<String, JointLoads> worst = new LinkedHashMap<>();
		List<String> phases = new ArrayList<>();
		for (int ph = 0; ph <= seps.size(); ph++) {
			double t0 = ph == 0 ? 0 : seps.get(ph - 1), t1 = ph < seps.size() ? seps.get(ph) : apogee;
			if (Double.isNaN(t1)) {
				t1 = t.length == 0 ? 0 : t[t.length - 1];
			}
			FlightConfiguration fc = Dynamics.stack(full, ph);
			double[] motor = new double[2];
			List<Point> pts = structure(fc, motor);
			double structureMass = pts.stream().mapToDouble(Point::m).sum();
			List<Joint> joints = joints(fc);
			int iq = -1, iqa = -1;
			double qMax = 0, qaMax = 0;
			Map<Long, Aero> cache = new LinkedHashMap<>();
			for (int i = br.index(t0); i >= 0 && i < t.length && t[i] <= t1 + 1e-9; i++) {
				if (ph > 0 && t[i] < t0 + 1e-9) {
					continue;
				}
				double v = br.airspeed(i), q = 0.5 * rho[i] * v * v;
				if (Double.isNaN(q)) {
					continue;
				}
				if (q > qMax) {
					qMax = q;
					iq = i;
				}
				double qa = q * Math.abs(Math.sin(Double.isNaN(aoa[i]) ? 0 : aoa[i]));
				if (v > 30 && qa > qaMax) {
					qaMax = qa;
					iqa = i;
				}
				double motorNow = Math.max(0, (Double.isNaN(mass[i]) ? structureMass + motor[0] : mass[i]) - structureMass);
				double machNow = Double.isNaN(mach[i]) ? 0.1 : mach[i];
				Aero a = cache.computeIfAbsent(Math.round(machNow * 20), k -> aero(fc, machNow));
				for (Joint jt : joints) {
					double n = axial(pts, motorNow, motor[1], nz(thrust[i]), nz(drag[i]), a, q, jt.x());
					JointLoads w = worst.computeIfAbsent(jt.name(), k -> new JointLoads(jt));
					if (w.axial == null || n > w.axial.axial()) {
						w.axial = new Load(n, 0, t[i], machNow, q);
					}
				}
			}
			if (iq < 0) {
				continue;
			}
			phases.add((ph == 0 ? "full vehicle" : "after separation " + ph) + ": max q " + Units.fmt(qMax, Dim.PRESSURE)
					+ " at t=" + Units.num(t[iq]) + " s (Mach " + Units.num(mach[iq]) + ")");
			double alphaGust = Math.atan2(gustSpeed, br.airspeed(iq));
			Aero aq = aero(fc, mach[iq]);
			double motorQ = Math.max(0, (Double.isNaN(mass[iq]) ? structureMass + motor[0] : mass[iq]) - structureMass);
			for (Joint jt : joints) {
				JointLoads w = worst.computeIfAbsent(jt.name(), k -> new JointLoads(jt));
				double m = Math.abs(moment(pts, motorQ, motor[1], aq, qMax, alphaGust, jt.x()));
				double n = axial(pts, motorQ, motor[1], nz(thrust[iq]), nz(drag[iq]), aq, qMax, jt.x());
				if (w.gust == null || m > w.gust.moment()) {
					w.gust = new Load(n, m, t[iq], mach[iq], qMax);
					w.gustAlpha = alphaGust;
				}
				if (iqa >= 0) {
					Aero aa = aero(fc, mach[iqa]);
					double motorA = Math.max(0, mass[iqa] - structureMass);
					double qA = 0.5 * rho[iqa] * br.airspeed(iqa) * br.airspeed(iqa);
					double ms = Math.abs(moment(pts, motorA, motor[1], aa, qA, aoa[iqa], jt.x()));
					if (w.sim == null || ms > w.sim.moment()) {
						w.sim = new Load(0, ms, t[iqa], mach[iqa], qA);
						w.simAlpha = aoa[iqa];
					}
				}
			}
		}
		if (worst.isEmpty()) {
			throw new io.github.openrocketmcp.mcp.ToolException("No ascent data or no joints to analyse.");
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		String worstName = null;
		double worstStress = 0;
		for (JointLoads w : worst.values()) {
			Joint jt = w.joint;
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("joint", jt.name());
			r.put("station", Units.fmt(jt.x(), Dim.LENGTH) + " from the nose tip");
			if (w.axial != null) {
				r.put("maxAxialCompression", Units.fmt(w.axial.axial(), Dim.FORCE) + " at t=" + Units.num(w.axial.time()) + " s");
			}
			if (w.gust != null) {
				r.put("bendingMomentMaxQGust", fmtMoment(w.gust.moment()) + " at t=" + Units.num(w.gust.time()) + " s with "
						+ Units.num(Math.toDegrees(w.gustAlpha)) + " deg AoA, axial " + Units.fmt(w.gust.axial(), Dim.FORCE));
			}
			if (w.sim != null) {
				r.put("bendingMomentSimulatedAoA", fmtMoment(w.sim.moment()) + " at t=" + Units.num(w.sim.time()) + " s ("
						+ Units.num(Math.toDegrees(Math.abs(w.simAlpha))) + " deg)");
			}
			if (jt.radius() > 0 && jt.thickness() > 0 && w.axial != null && w.gust != null) {
				double area = 2 * Math.PI * jt.radius() * jt.thickness();
				double sec = Math.PI * Math.pow(jt.radius(), 3) * jt.thickness();
				double stress = Math.max(Math.max(0, w.axial.axial()) / area,
						Math.max(0, w.gust.axial()) / area + w.gust.moment() * jt.radius() / sec);
				r.put("wallStress", Units.fmt(stress, Dim.PRESSURE) + " (thin tube r " + Units.fmt(jt.radius(), Dim.LENGTH) + ", wall "
						+ Units.fmt(jt.thickness(), Dim.LENGTH) + "; axial + bending)");
				r.put("requiredAllowable", Units.fmt(stress * safetyFactor, Dim.PRESSURE) + " (x " + Units.num(safetyFactor) + ")");
				if (!Double.isNaN(allowable)) {
					r.put("margin", Units.num(allowable / (stress * safetyFactor) - 1)
							+ (allowable >= stress * safetyFactor ? " (OK)" : " (NEGATIVE)"));
				}
				if (stress > worstStress) {
					worstStress = stress;
					worstName = jt.name();
				}
			}
			rows.add(r);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("phases", phases);
		out.put("gust", Units.fmt(gustSpeed, Dim.VELOCITY) + " crosswind at each phase's max q");
		out.put("joints", rows);
		if (worstName != null) {
			out.put("highestWallStress", worstName + ": " + Units.fmt(worstStress, Dim.PRESSURE));
		}
		out.put("notes", List.of(
				"Rigid-body quasi-static loads with inertial relief (no structural dynamics, no parachute-opening or landing loads "
						+ "- see recovery_analysis for opening loads).",
				"Joint stress uses the thinner neighbouring wall as a thin tube; couplers, shear pins and fasteners carry the "
						+ "joint in reality - check them with these forces and moments. Thin tubes also fail by buckling below "
						+ "the material strength.",
				"Each stack is analysed from its separation to the next (or apogee); the worst value per joint is reported."));
		return out;
	}

	static String fmtMoment(double nm) {
		String metric = Units.num(nm) + " N·m";
		String imperial = Units.num(nm / 1.3558179483314) + " lbf·ft";
		return switch (Units.system()) {
			case METRIC -> metric;
			case IMPERIAL -> imperial;
			case BOTH -> metric + " (" + imperial + ")";
		};
	}
}
