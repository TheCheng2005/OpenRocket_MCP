package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Electrical checks for the flight electronics: battery capacity for the whole launch day, e-match firing current and
 * the voltage sag that resets altimeters (power budget), and the tracker's radio link from the pad to apogee and to
 * the landing point (link budget).
 */
public final class Electrical {
	private Electrical() {
	}

	// ------------------------------------------------------------------------------------------------ power budget

	/**
	 * One battery and what it powers. Currents in A, capacity in A·h, resistances in ohm, voltages in V; brownout is the
	 * lowest voltage the electronics keep running at (NaN when unknown).
	 */
	public record Circuit(String name, String battery, double voltage, double capacity, double internalResistance,
			double current, int pyroChannels, double brownout) {
	}

	/** The e-match and wiring a pyro channel fires through. */
	public record Pyro(double matchResistance, double allFireCurrent, double wiringResistance, double margin) {
	}

	/** How long the electronics must run: on the pad, in flight, and until the rocket is found. */
	public record Durations(double pad, double flight, double recovery) {
		double total() {
			return pad + flight + recovery;
		}
	}

	public static List<Circuit> circuits(JsonArray arr) {
		List<Circuit> out = new ArrayList<>();
		if (arr == null) {
			return out;
		}
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) {
				throw new ToolException("Each circuit is an object like {\"name\": \"Primary altimeter\", \"voltage\": 9, "
						+ "\"capacityMah\": 550, \"currentMa\": 12}; got " + e + ".");
			}
			JsonObject o = e.getAsJsonObject();
			String name = o.has("name") ? o.get("name").getAsString() : "circuit " + (out.size() + 1);
			double v = num(o, "voltage"), cap = num(o, "capacityMah") / 1000, ri = num(o, "internalResistance"),
					i = num(o, "currentMa") / 1000, pyro = num(o, "pyroChannels");
			if (!(v > 0) || !(cap > 0) || !(i >= 0)) {
				throw new ToolException("Circuit '" + name + "' needs voltage (V), capacityMah and currentMa.");
			}
			if (ri < 0 || pyro < 0 || pyro != Math.rint(pyro)) {
				throw new ToolException("Circuit '" + name + "': internalResistance cannot be negative and pyroChannels is a "
						+ "whole number (0 for a tracker).");
			}
			out.add(new Circuit(name, o.has("battery") ? o.get("battery").getAsString() : "", v, cap,
					Double.isNaN(ri) ? 0 : ri, i, Double.isNaN(pyro) ? 0 : (int) pyro, num(o, "brownoutVoltage")));
		}
		return out;
	}

	/** A number from team data or a call, NaN when absent; anything else is refused by name. */
	static double num(JsonObject o, String k) {
		if (!o.has(k) || o.get(k).isJsonNull()) {
			return Double.NaN;
		}
		try {
			return o.get(k).getAsDouble();
		} catch (RuntimeException e) {
			throw new ToolException("'" + k + "' must be a plain number in its stated unit, not " + o.get(k) + ".");
		}
	}

	/** Launch to the last landing in the simulation (s). */
	public static double flightTime(Simulation sim) {
		double end = 0;
		for (FlightDataBranch b : sim.getSimulatedData().getBranches()) {
			FlightEvent hit = b.getFirstEvent(FlightEvent.Type.GROUND_HIT);
			end = Math.max(end, hit != null ? hit.getTime() : Branch.of(b).last(FlightDataType.TYPE_TIME));
		}
		return end;
	}

	/**
	 * Per circuit: capacity for the day (rated capacity x derating against current x time), the current through an
	 * e-match (V / (internal + match + wiring)) against its all-fire current x margin, and the battery voltage while a
	 * channel fires against the brownout voltage.
	 */
	public static List<Map<String, Object>> power(List<Circuit> circuits, Pyro pyro, Durations d, double derating) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Circuit c : circuits) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("circuit", c.name() + (c.battery().isEmpty() ? "" : " (" + c.battery() + ")"));
			double usable = c.capacity() * derating;
			double needed = c.current() * d.total() / 3600;
			double runtime = c.current() > 0 ? usable / c.current() * 3600 : Double.POSITIVE_INFINITY;
			m.put("needs", Units.num(needed * 1000) + " mAh (" + Units.num(c.current() * 1000) + " mA for " + hours(d.total()) + ")");
			m.put("has", Units.num(usable * 1000) + " mAh usable (" + Units.num(c.capacity() * 1000) + " mAh x " + Units.num(derating) + ")");
			m.put("runtime", Double.isInfinite(runtime) ? "no load" : hours(runtime) + " on the pad and after");
			List<String> issues = new ArrayList<>();
			String status = "PASS";
			if (needed > usable) {
				status = "FAIL";
				issues.add("runs flat after " + hours(runtime) + ": a bigger battery, a fresh one on the pad, or less wait");
			} else if (needed > 0.7 * usable) {
				status = "WARN";
				issues.add("less than 30% to spare: a delay on the pad or a long search uses it up");
			}
			if (c.pyroChannels() > 0) {
				double r = c.internalResistance() + pyro.matchResistance() + pyro.wiringResistance();
				double i = c.voltage() / r;
				double sag = c.voltage() - i * c.internalResistance();
				m.put("pyroCurrent", Units.num(i) + " A through each e-match (" + Units.num(pyro.matchResistance()) + " ohm match, "
						+ Units.num(c.internalResistance()) + " ohm battery, " + Units.num(pyro.wiringResistance()) + " ohm wiring)");
				if (i < pyro.allFireCurrent() * pyro.margin()) {
					status = "FAIL";
					issues.add("below " + Units.num(pyro.margin()) + " x the " + Units.num(pyro.allFireCurrent())
							+ " A all-fire current: a charge may not light; use a battery that can deliver the current "
							+ "(LiPo) or a lower-resistance match");
				}
				m.put("voltageWhileFiring", Units.num(sag) + " V (from " + Units.num(c.voltage()) + " V)");
				if (!Double.isNaN(c.brownout()) && sag < c.brownout()) {
					if (!"FAIL".equals(status)) {
						status = "WARN";
					}
					issues.add("drops below the " + Units.num(c.brownout()) + " V the electronics need while a charge fires: "
							+ "the altimeter can reset mid-flight (brownout); add a capacitor or a separate pyro battery");
				}
			}
			m.put("status", status);
			if (!issues.isEmpty()) {
				m.put("issues", issues);
			}
			out.add(m);
		}
		return out;
	}

	public static String hours(double s) {
		return s >= 3600 ? Units.num(s / 3600) + " h" : Units.num(s / 60) + " min";
	}

	// ------------------------------------------------------------------------------------------------- link budget

	/** The radio: frequency (Hz), powers and sensitivity in dBm, gains and losses in dB, antenna heights in m. */
	public record Radio(double frequency, double txPower, double txGain, double rxGain, double sensitivity, double losses,
			double requiredMargin, double groundStationHeight, double rocketHeightOnGround) {
		double lambda() {
			return 299_792_458.0 / frequency;
		}

		double budget() {
			return txPower + txGain + rxGain - losses - sensitivity;
		}
	}

	/** Free-space path loss (dB) over {@code d} m. */
	static double fspl(double d, double f) {
		return 20 * Math.log10(Math.max(d, 1)) + 20 * Math.log10(f) + 20 * Math.log10(4 * Math.PI / 299_792_458.0);
	}

	/**
	 * Path loss along the ground: free space out to the two-ray crossover 4 pi h1 h2 / lambda, then the plane-earth
	 * 40 log d - 20 log h1 - 20 log h2 (the ground reflection cancels the direct wave). With an antenna lying in the
	 * grass, this is what limits finding a landed rocket.
	 */
	static double groundLoss(double d, Radio r) {
		double h1 = r.groundStationHeight(), h2 = r.rocketHeightOnGround();
		double crossover = 4 * Math.PI * h1 * h2 / r.lambda();
		if (d <= crossover) {
			return fspl(d, r.frequency());
		}
		return Math.max(fspl(d, r.frequency()), 40 * Math.log10(d) - 20 * Math.log10(h1) - 20 * Math.log10(h2));
	}

	/** Longest ground distance with the required margin (bisection on the monotonic path loss). */
	static double groundRange(Radio r) {
		double lo = 1, hi = 1e6;
		if (r.budget() - groundLoss(hi, r) >= r.requiredMargin()) {
			return hi;
		}
		for (int i = 0; i < 80; i++) {
			double mid = Math.sqrt(lo * hi);
			if (r.budget() - groundLoss(mid, r) >= r.requiredMargin()) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	/**
	 * Margin at the farthest point of the flight (line of sight, free space), at apogee, and at each landing (on the
	 * ground). {@code east}/{@code north}: the ground station's offset from the pad (m).
	 */
	public static Map<String, Object> link(Simulation sim, Radio r, double east, double north, double extraLanding) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("radio", Units.num(r.frequency() / 1e6) + " MHz, " + Units.num(r.txPower()) + " dBm, antennas "
				+ Units.num(r.txGain()) + " / " + Units.num(r.rxGain()) + " dBi, receiver " + Units.num(r.sensitivity())
				+ " dBm, losses " + Units.num(r.losses()) + " dB: " + Units.num(r.budget()) + " dB to spend on the path");
		List<Map<String, Object>> rows = new ArrayList<>();
		String worst = "PASS";
		List<String> seen = new ArrayList<>();
		for (FlightDataBranch b : sim.getSimulatedData().getBranches()) {
			// Stages can share a name: number the repeats so each row says which one it is.
			long same = seen.stream().filter(b.getName()::equals).count();
			seen.add(b.getName());
			Branch br = Branch.of(b);
			double[] x = br.col(FlightDataType.TYPE_POSITION_X), y = br.col(FlightDataType.TYPE_POSITION_Y),
					h = br.col(FlightDataType.TYPE_ALTITUDE);
			double far = 0, farT = 0, farH = 0;
			for (int i = 0; i < br.size(); i++) {
				if (h[i] < 10) {
					continue; // near the ground the plane-earth loss applies (afterLanding), not free space
				}
				double dx = x[i] - east, dy = y[i] - north;
				double d = Math.sqrt(dx * dx + dy * dy + h[i] * h[i]);
				if (d > far) {
					far = d;
					farT = br.time[i];
					farH = h[i];
				}
			}
			double apogee = Sims.eventTime(b, FlightEvent.Type.APOGEE);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("branch", same == 0 ? b.getName() : b.getName() + " (" + (same + 1) + ")");
			// A part that never climbs above 10 m (a booster dropped on the pad) has only a ground path.
			double mFar = far > 0 ? r.budget() - fspl(far, r.frequency()) : Double.POSITIVE_INFINITY;
			if (far > 0) {
				m.put("farthestInFlight", Units.fmt(far, Dim.DISTANCE) + " at t=" + Units.num(farT) + " s ("
						+ Units.fmt(farH, Dim.DISTANCE) + " up): " + db(mFar));
			}
			if (!Double.isNaN(apogee)) {
				int ia = br.index(apogee);
				double dx = x[ia] - east, dy = y[ia] - north;
				m.put("atApogee", db(r.budget() - fspl(Math.sqrt(dx * dx + dy * dy + h[ia] * h[ia]), r.frequency())));
			}
			FlightEvent hit = b.getFirstEvent(FlightEvent.Type.GROUND_HIT);
			String status = mFar >= r.requiredMargin() ? "PASS" : mFar >= 0 ? "WARN" : "FAIL";
			if (hit != null) {
				int il = br.size() - 1;
				double dx = x[il] - east, dy = y[il] - north;
				double d = Math.hypot(dx, dy) + extraLanding;
				double mLand = r.budget() - groundLoss(d, r);
				m.put("afterLanding", Units.fmt(d, Dim.DISTANCE) + " away on the ground" + (extraLanding > 0 ? " (incl. "
						+ Units.fmt(extraLanding, Dim.DISTANCE) + " dispersion)" : "") + ": " + db(mLand));
				status = worse(status, mLand >= r.requiredMargin() ? "PASS" : mLand >= 0 ? "WARN" : "FAIL");
			}
			m.put("status", status);
			worst = worse(worst, status);
			rows.add(m);
		}
		out.put("paths", rows);
		out.put("groundRange", Units.fmt(groundRange(r), Dim.DISTANCE) + " with " + Units.num(r.requiredMargin())
				+ " dB margin once landed (ground station antenna " + Units.fmt(r.groundStationHeight(), Dim.DISTANCE)
				+ " up, rocket antenna " + Units.fmt(r.rocketHeightOnGround(), Dim.DISTANCE) + " off the ground)");
		out.put("status", worst);
		List<String> notes = new ArrayList<>();
		if (!"PASS".equals(worst)) {
			notes.add("More margin: a directional antenna at the ground station (a 3-element Yagi is ~7 dBi), raise it, a "
					+ "slower data rate or higher spreading factor (better sensitivity), or more transmit power within your "
					+ "licence / ISM limits.");
		}
		notes.add("Free-space loss in flight; on the ground the plane-earth model (both antennas low) usually sets the range. "
				+ "Hills, trees and the airframe (carbon fibre and metal block RF) take more: the margin is for them and for "
				+ "a tumbling antenna's nulls.");
		out.put("notes", notes);
		return out;
	}

	private static String db(double margin) {
		return (margin >= 0 ? "+" : "") + Units.num(margin) + " dB margin";
	}

	private static String worse(String a, String b) {
		List<String> order = List.of("PASS", "WARN", "FAIL");
		return order.indexOf(a) >= order.indexOf(b) ? a : b;
	}
}
