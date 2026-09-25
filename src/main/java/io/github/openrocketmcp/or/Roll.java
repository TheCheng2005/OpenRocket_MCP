package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.units.Units;

/**
 * Roll from fin cant / misalignment, simulated by OpenRocket (roll forcing and damping), against the pitch natural
 * frequency f_p = sqrt(C1 / I) / 2 pi with C1 = 1/2 rho V^2 A sum CNa_i (x_i - x_cg). When the roll rate crosses the
 * pitch frequency, roll-pitch coupling can amplify the angle of attack (roll resonance / lock-in).
 */
public final class Roll {
	private Roll() {
	}

	/** One cant value's result. */
	public record Case(double cant, double maxRoll, double rollAtBurnout, double crossingTime, double crossingMach,
			double crossingFreq, double maxAoa, String error) {
	}

	static void cant(RocketComponent root, String finId, double angle) {
		for (RocketComponent c : root) {
			if (c instanceof FinSet f && (finId == null || f.getID().toString().equals(finId))) {
				f.setCantAngle(angle);
			}
		}
	}

	public static List<Case> sweep(Simulation base, OpenRocketDocument doc, String finId, List<Double> cants) {
		List<Simulation> sims = new ArrayList<>();
		for (double c : cants) {
			sims.add(Variants.of(base, doc, r -> cant(r, finId, c), null));
		}
		List<Variants.Run> runs = Variants.runAll(sims);
		List<Case> out = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			Variants.Run r = runs.get(i);
			out.add(r.ok() ? evaluate(r.sim(), cants.get(i))
					: new Case(cants.get(i), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, r.error()));
		}
		return out;
	}

	/** Roll rate (rev/s) extremes and the first crossing of the pitch natural frequency during the ascent. */
	static Case evaluate(Simulation sim, double cantAngle) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double[] t = br.time, roll = br.col(FlightDataType.TYPE_ROLL_RATE), rho = br.col(FlightDataType.TYPE_AIR_DENSITY),
				cg = br.col(FlightDataType.TYPE_CG_LOCATION), il = br.col(FlightDataType.TYPE_LONGITUDINAL_INERTIA),
				mach = br.col(FlightDataType.TYPE_MACH_NUMBER), aoa = br.col(FlightDataType.TYPE_AOA);
		double rail = Sims.eventTime(b, FlightEvent.Type.LAUNCHROD), apogee = Sims.eventTime(b, FlightEvent.Type.APOGEE);
		double burnout = Sims.eventTime(b, FlightEvent.Type.BURNOUT);
		List<Double> seps = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() == FlightEvent.Type.STAGE_SEPARATION) {
				seps.add(e.getTime());
			}
		}
		FlightConfiguration full = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		Map<String, Dynamics.Aero> cache = new HashMap<>();
		Map<Integer, FlightConfiguration> stacks = new HashMap<>();
		double maxRoll = 0, maxAoa = 0, cross = Double.NaN, crossMach = Double.NaN, crossF = Double.NaN;
		double prevDiff = Double.NaN;
		int i0 = Double.isNaN(rail) ? 0 : br.index(rail), i1 = Double.isNaN(apogee) ? t.length - 1 : br.index(apogee);
		for (int i = Math.max(0, i0); i <= i1 && i < t.length; i++) {
			double rps = Math.abs(roll[i]) / (2 * Math.PI);
			if (!Double.isNaN(rps)) {
				maxRoll = Math.max(maxRoll, rps);
			}
			double v = br.airspeed(i);
			if (!(v > 30) || Double.isNaN(cg[i]) || Double.isNaN(il[i]) || Double.isNaN(rps)) {
				prevDiff = Double.NaN;
				continue;
			}
			if (!Double.isNaN(aoa[i])) {
				maxAoa = Math.max(maxAoa, Math.abs(aoa[i]));
			}
			int dropped = 0;
			for (double s : seps) {
				if (t[i] >= s - 1e-9) {
					dropped++;
				}
			}
			int dr = dropped;
			FlightConfiguration fc = stacks.computeIfAbsent(dr, n -> Dynamics.stack(full, n));
			double m = mach[i];
			Dynamics.Aero a = cache.computeIfAbsent(dr + ":" + Math.round(m * 50), k -> Dynamics.aero(fc, m));
			double m1 = 0;
			for (int j = 0; j < a.cna().length; j++) {
				m1 += a.cna()[j] * (a.x()[j] - cg[i]);
			}
			double c1 = 0.5 * rho[i] * v * v * a.refArea() * m1;
			if (c1 <= 0 || il[i] <= 0) {
				prevDiff = Double.NaN;
				continue;
			}
			double fp = Math.sqrt(c1 / il[i]) / (2 * Math.PI);
			double diff = rps - fp;
			if (Double.isNaN(cross) && !Double.isNaN(prevDiff) && Math.signum(diff) != Math.signum(prevDiff)) {
				cross = t[i];
				crossMach = m;
				crossF = fp;
			}
			prevDiff = diff;
		}
		double atBurnout = Double.isNaN(burnout) ? Double.NaN : Math.abs(br.at(FlightDataType.TYPE_ROLL_RATE, burnout)) / (2 * Math.PI);
		return new Case(cantAngle, maxRoll, atBurnout, cross, crossMach, crossF, maxAoa, null);
	}

	public static Map<String, Object> render(List<Case> cases, double maxRollRate, int finCount) {
		List<Map<String, Object>> rows = new ArrayList<>();
		double tolerance = Double.NaN;
		for (Case c : cases) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("cant", Units.num(Math.toDegrees(c.cant())) + " deg");
			if (c.error() != null) {
				m.put("error", c.error());
				rows.add(m);
				continue;
			}
			m.put("maxRollRate", rps(c.maxRoll()));
			m.put("rollRateAtBurnout", rps(c.rollAtBurnout()));
			m.put("maxAngleOfAttack", Units.num(Math.toDegrees(c.maxAoa())) + " deg");
			m.put("pitchResonance", Double.isNaN(c.crossingTime()) ? "no crossing"
					: "roll rate crosses the pitch frequency (" + Units.num(c.crossingFreq()) + " Hz) at t=" + Units.num(c.crossingTime())
							+ " s, Mach " + Units.num(c.crossingMach()));
			rows.add(m);
			boolean ok = c.maxRoll() <= maxRollRate && Double.isNaN(c.crossingTime());
			if (ok && (Double.isNaN(tolerance) || Math.abs(c.cant()) > tolerance)) {
				tolerance = Math.abs(c.cant());
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("cases", rows);
		out.put("alignmentTolerance", Double.isNaN(tolerance) ? "none of the tested cants keeps the roll below "
				+ Units.num(maxRollRate) + " rev/s without a pitch-frequency crossing"
				: "fin set cant up to " + Units.num(Math.toDegrees(tolerance)) + " deg keeps the roll below " + Units.num(maxRollRate)
						+ " rev/s with no pitch-frequency crossing (one fin misaligned by X deg is roughly a fin-set cant of X / "
						+ finCount + ")");
		out.put("notes", List.of(
				"OpenRocket simulates roll from fin cant with its roll forcing and damping model; real misalignment, fin-tab "
						+ "asymmetry and wind add roll it does not see, so treat the tolerance as the build target, not a margin.",
				"A crossing during boost that is passed quickly is usually benign; a roll rate that stays near the pitch "
						+ "frequency can amplify the angle of attack (roll resonance) - avoid it with better alignment or intentional spin.",
				"Cameras and many avionics prefer low roll; set maxRollRate for yours."));
		return out;
	}

	static String rps(double v) {
		return (Math.abs(v) < 1e-3 ? "0" : Units.num(v)) + " rev/s";
	}
}
