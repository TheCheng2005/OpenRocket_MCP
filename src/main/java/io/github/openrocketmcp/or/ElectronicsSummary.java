package io.github.openrocketmcp.or;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.report.Reports;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;

/**
 * The team's electronics from the standards (sensors, altimeter choices, battery circuits, radio), and one summary of
 * every electronics check on a flight, shared by the electronics tools, the flight card, the design status and the
 * review report so they all say the same thing.
 */
public final class ElectronicsSummary {
	private ElectronicsSummary() {
	}

	static JsonObject section(Standards std, String key) {
		JsonObject data = std.data();
		JsonObject el = data.has("electronics") && data.get("electronics").isJsonObject() ? data.getAsJsonObject("electronics") : null;
		return el != null && el.has(key) && el.get(key).isJsonObject() ? el.getAsJsonObject(key) : new JsonObject();
	}

	static double num(JsonObject o, String k, double fallback) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : fallback;
	}

	public static List<SensorSim.Spec> sensors(Standards std) {
		JsonObject data = std.data();
		JsonObject el = data.has("electronics") && data.get("electronics").isJsonObject() ? data.getAsJsonObject("electronics") : null;
		return SensorSim.specs(el != null && el.has("sensors") ? el.getAsJsonArray("sensors") : null);
	}

	public static AltimeterSettings.Options altimeter(Standards std) {
		return new AltimeterSettings.Options(std.q("electronics.altimeter.backupDrogueDelay", Dim.TIME, 1),
				std.q("electronics.altimeter.backupMainOffset", Dim.DISTANCE, 30.48),
				std.q("electronics.altimeter.baroUnreliableAboveMach", Dim.DIMENSIONLESS, 0.7),
				std.q("electronics.altimeter.machLockoutMargin", Dim.TIME, 1));
	}

	public static List<Electrical.Circuit> circuits(Standards std) {
		JsonObject pw = section(std, "power");
		JsonArray arr = pw.has("circuits") ? pw.getAsJsonArray("circuits") : null;
		return Electrical.circuits(arr);
	}

	public static Electrical.Pyro pyro(Standards std, double resistance, double allFire) {
		JsonObject pw = section(std, "power");
		JsonObject em = pw.has("ematch") && pw.get("ematch").isJsonObject() ? pw.getAsJsonObject("ematch") : new JsonObject();
		return new Electrical.Pyro(Double.isNaN(resistance) ? num(em, "resistance", 1.6) : resistance,
				Double.isNaN(allFire) ? num(em, "allFireCurrent", 1.0) : allFire, num(em, "wiringResistance", 0.3),
				num(em, "currentMargin", 2));
	}

	public static double derating(Standards std) {
		return num(section(std, "power"), "capacityDerating", 0.8);
	}

	public static Electrical.Durations durations(Standards std, Simulation sim, double pad, double after) {
		return new Electrical.Durations(Double.isNaN(pad) ? std.q("electronics.power.padWait", Dim.TIME, 7200) : pad,
				Electrical.flightTime(sim), Double.isNaN(after) ? std.q("electronics.power.recoveryTime", Dim.TIME, 7200) : after);
	}

	/** The team's radio, with any of the call's values (NaN = the team's). */
	public static Electrical.Radio radio(Standards std, double freqMhz, double tx, double txGain, double rxGain, double sens,
			double losses, double margin, double stationHeight) {
		JsonObject ro = section(std, "radio");
		return new Electrical.Radio(or(freqMhz, num(ro, "frequencyMhz", 915)) * 1e6, or(tx, num(ro, "txPowerDbm", 20)),
				or(txGain, num(ro, "txAntennaGainDbi", 2.15)), or(rxGain, num(ro, "rxAntennaGainDbi", 2.15)),
				or(sens, num(ro, "rxSensitivityDbm", -123)), or(losses, num(ro, "lossesDb", 3)),
				or(margin, num(ro, "requiredMarginDb", 10)),
				or(stationHeight, std.q("electronics.radio.groundStationHeight", Dim.LENGTH, 2)),
				std.q("electronics.radio.rocketAntennaHeightOnGround", Dim.LENGTH, 0.1));
	}

	private static double or(double v, double fallback) {
		return Double.isNaN(v) ? fallback : v;
	}

	/** The avionics bay: a coupler named like one ("Av-bay", "avionics", "ebay"), or null. */
	public static RocketComponent bay(RocketComponent rocket) {
		for (RocketComponent c : rocket) {
			String n = c.getName().toLowerCase(Locale.ROOT);
			if (c instanceof TubeCoupler && (n.contains("av-bay") || n.contains("avbay") || n.contains("avionics")
					|| n.contains("ebay") || n.contains("e-bay"))) {
				return c;
			}
		}
		return null;
	}

	/** One problem for the design status: what, and the tool that shows it. */
	public record Issue(String status, String what, String tool) {
	}

	/** Every electronics check on this flight. Missing team data gives a note, not an error. */
	public record Summary(Map<String, Object> altimeters, List<Map<String, Object>> sensors, List<Map<String, Object>> power,
			Map<String, Object> radio, List<String> notes) {

		public List<Issue> issues() {
			List<Issue> out = new ArrayList<>();
			for (Map<String, Object> r : sensors) {
				if ("FAIL".equals(r.get("status")) && !String.valueOf(r.get("quantity")).startsWith("parachute")) {
					out.add(new Issue("FAIL", r.get("sensor") + ": " + r.get("quantity") + " " + r.get("peak") + " exceeds "
							+ r.get("range"), "sensor_check"));
				}
			}
			for (Map<String, Object> r : power) {
				if (!"PASS".equals(r.get("status"))) {
					Object why = r.get("issues");
					out.add(new Issue((String) r.get("status"), r.get("circuit") + ": "
							+ (why instanceof List<?> l ? String.join("; ", l.stream().map(String::valueOf).toList()) : String.valueOf(why)),
							"power_budget"));
				}
			}
			if (radio != null && !"PASS".equals(radio.get("status"))) {
				out.add(new Issue((String) radio.get("status"), "tracker radio link short of the wanted margin", "radio_link"));
			}
			if (altimeters != null && altimeters.get("warnings") instanceof List<?> w) {
				for (Object x : w) {
					out.add(new Issue("WARN", String.valueOf(x), "altimeter_settings"));
				}
			}
			return out;
		}
	}

	public static Summary of(Designs.Design d, Simulation sim, Standards std) {
		List<String> notes = new ArrayList<>();
		Map<String, Object> alt = null;
		try {
			RocketComponent bay = bay(d.doc.getRocket());
			alt = AltimeterSettings.sheet(sim, altimeter(std), bay == null ? 0 : AltimeterSettings.bayVolume(bay),
					bay == null ? null : bay.getName(), null);
		} catch (ToolException | IOException e) {
			notes.add("Altimeter settings: " + e.getMessage());
		}
		List<Map<String, Object>> sensors = List.of();
		List<SensorSim.Spec> specs = sensors(std);
		if (specs.isEmpty()) {
			notes.add("No sensors in electronics.sensors: sensor ranges not checked.");
		} else {
			sensors = SensorSim.check(SensorSim.trace(sim, null, 200, 0, 0), specs,
					std.q("electronics.altimeter.baroUnreliableAboveMach", Dim.DIMENSIONLESS, 0.7));
		}
		List<Map<String, Object>> power = List.of();
		List<Electrical.Circuit> circuits = circuits(std);
		if (circuits.isEmpty()) {
			notes.add("No battery circuits in electronics.power: power not checked.");
		} else {
			power = Electrical.power(circuits, pyro(std, Double.NaN, Double.NaN), durations(std, sim, Double.NaN, Double.NaN),
					derating(std));
		}
		Map<String, Object> radio = Electrical.link(sim, radio(std, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
				Double.NaN, Double.NaN, Double.NaN), 0, 0, 0);
		return new Summary(alt, sensors, power, radio, notes);
	}

	/** Markdown for the flight card and the review report. */
	@SuppressWarnings("unchecked")
	public static String markdown(Summary s, String heading) {
		StringBuilder md = new StringBuilder(heading).append("\n\n");
		if (s.altimeters() != null) {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Map<String, Object> ch : (List<Map<String, Object>>) s.altimeters().get("channels")) {
				Map<String, Object> r = new LinkedHashMap<>();
				r.put("parachute", ch.get("device"));
				r.put("primary altimeter", ch.get("primary"));
				r.put("backup altimeter", ch.getOrDefault("backup", ""));
				rows.add(r);
			}
			md.append(Reports.table(rows)).append('\n');
			md.append("Mach lockout: ").append(((Map<String, Object>) s.altimeters().get("lockout")).get("machLockout")).append(". ");
			md.append("Static ports: ").append(String.valueOf(s.altimeters().get("staticPorts")).split(";")[0]).append(".\n\n");
		}
		List<String> lines = new ArrayList<>();
		for (Map<String, Object> r : s.sensors()) {
			// Openings are an upper bound that matters only to flight software: sensor_check has them, the card does not.
			if (!"PASS".equals(r.get("status")) && !"INFO".equals(r.get("status"))
					&& !String.valueOf(r.get("quantity")).startsWith("parachute")) {
				lines.add(r.get("status") + " sensor: " + r.get("sensor") + ", " + r.get("quantity") + " " + r.get("peak")
						+ (r.containsKey("note") ? " (" + r.get("note") + ")" : ""));
			}
		}
		for (Map<String, Object> r : s.power()) {
			lines.add(r.get("status") + " power: " + r.get("circuit") + ", needs " + r.get("needs") + ", has " + r.get("has")
					+ (r.containsKey("pyroCurrent") ? "; e-match " + String.valueOf(r.get("pyroCurrent")).split(" through")[0] : "")
					+ (r.containsKey("issues") ? " (" + String.join("; ", (List<String>) r.get("issues")) + ")" : ""));
		}
		if (s.radio() != null) {
			StringBuilder rl = new StringBuilder(s.radio().get("status") + " radio: ");
			for (Map<String, Object> p : (List<Map<String, Object>>) s.radio().get("paths")) {
				rl.append(p.get("branch")).append(" ").append(String.valueOf(p.get("farthestInFlight")).replaceAll(".*: ", "in flight "));
				if (p.containsKey("afterLanding")) {
					rl.append(", ").append(String.valueOf(p.get("afterLanding")).replaceAll(".*: ", "landed "));
				}
				rl.append("; ");
			}
			lines.add(rl.toString().replaceAll("; $", ""));
		}
		for (String l : lines) {
			md.append("- ").append(l).append('\n');
		}
		for (String n : s.notes()) {
			md.append("- ").append(n).append('\n');
		}
		return md.append('\n').toString();
	}
}
