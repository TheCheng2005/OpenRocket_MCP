package io.github.openrocketmcp.or;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * What the flight computer would have measured on a simulated flight: accelerometers, gyroscopes, barometer and GPS,
 * sampled at a fixed rate with the sensors' noise, resolution and range, plus the true values. For testing flight
 * software (launch, burnout, apogee and main-altitude detection) before it flies, and for checking that each sensor's
 * range covers the flight.
 *
 * <p>An accelerometer measures specific force, not acceleration: thrust and aerodynamic force over mass, and the support
 * of the pad or rail, but not gravity. Along the rocket's axis (x, toward the nose) that is (thrust - drag) / mass in
 * flight, +1 g cos(rail angle) on the pad, and the parachute's pull under canopy. Across the axis (y) it is the
 * normal force CN q A / mass, and g sin(rail angle) on the rail. Body rates come from OpenRocket's roll, pitch and yaw
 * rates; pressure and temperature are the ambient air at the rocket's altitude (a sealed, ported bay lags them, and
 * static ports read low near Mach 1: see {@link #baroWindow}).
 */
public final class SensorSim {
	private SensorSim() {
	}

	static final double G = Atmosphere.G0;

	/** One sensor. Ranges and noise in its own units: g, deg/s, Pa or m. */
	public record Spec(String id, String name, String type, double range, double noise, double resolution, double min,
			double max) {
	}

	/** True values at fixed time steps (the flight with pad time before and rest after). */
	public record Trace(double[] t, double[] axial, double[] lateral, double[] roll, double[] pitch, double[] yaw,
			double[] pressure, double[] temperature, double[] lat, double[] lon, double[] altMsl, double[] altAgl,
			double[] vz, double[] speed, double[] mach, String[] phase, List<double[]> eventTimes, List<String> eventNames,
			double padTime) {
		public int size() {
			return t.length;
		}
	}

	/** The team's sensors from the standards (electronics.sensors), or the arguments' list. */
	public static List<Spec> specs(JsonArray arr) {
		List<Spec> out = new ArrayList<>();
		if (arr == null) {
			return out;
		}
		for (JsonElement e : arr) {
			JsonObject o = e.getAsJsonObject();
			String type = o.has("type") ? o.get("type").getAsString().toLowerCase(Locale.ROOT) : "";
			if (!List.of("accelerometer", "gyroscope", "barometer", "gps").contains(type)) {
				throw new ToolException("Sensor type must be accelerometer, gyroscope, barometer or gps (got '" + type + "').");
			}
			String id = o.has("id") ? o.get("id").getAsString() : type.substring(0, Math.min(4, type.length())) + out.size();
			if (!id.matches("[A-Za-z][A-Za-z0-9_]*")) {
				throw new ToolException("Sensor id '" + id + "' must be letters, digits and underscores (it names CSV columns).");
			}
			out.add(new Spec(id, o.has("name") ? o.get("name").getAsString() : id, type, num(o, "range", Double.NaN),
					num(o, "noise", 0), num(o, "resolution", 0), num(o, "min", Double.NaN), num(o, "max", Double.NaN)));
		}
		return out;
	}

	private static double num(JsonObject o, String k, double d) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : d;
	}

	/** Samples the branch at {@code rate} Hz, with {@code padTime} s on the pad before ignition and rest after landing. */
	public static Trace trace(Simulation sim, String branchName, double rate, double padTime, double restTime) {
		FlightDataBranch b = null;
		for (FlightDataBranch fb : sim.getSimulatedData().getBranches()) {
			if (branchName == null || fb.getName().equalsIgnoreCase(branchName)) {
				b = fb;
				break;
			}
		}
		if (b == null) {
			List<String> names = new ArrayList<>();
			for (FlightDataBranch fb : sim.getSimulatedData().getBranches()) {
				names.add(fb.getName());
			}
			throw new ToolException("No flight branch '" + branchName + "'. Branches: " + names);
		}
		Branch br = Branch.of(b);
		double[] time = br.time;
		double[] thrust = br.col(FlightDataType.TYPE_THRUST_FORCE), drag = br.col(FlightDataType.TYPE_DRAG_FORCE),
				mass = br.col(FlightDataType.TYPE_MASS), cn = br.col(FlightDataType.TYPE_NORMAL_FORCE_COEFF),
				aref = br.col(FlightDataType.TYPE_REFERENCE_AREA), rho = br.col(FlightDataType.TYPE_AIR_DENSITY),
				mach = br.col(FlightDataType.TYPE_MACH_NUMBER), sos = br.col(FlightDataType.TYPE_SPEED_OF_SOUND),
				roll = br.col(FlightDataType.TYPE_ROLL_RATE), pitch = br.col(FlightDataType.TYPE_PITCH_RATE),
				yaw = br.col(FlightDataType.TYPE_YAW_RATE), p = br.col(FlightDataType.TYPE_AIR_PRESSURE),
				temp = br.col(FlightDataType.TYPE_AIR_TEMPERATURE), lat = br.col(FlightDataType.TYPE_LATITUDE),
				lon = br.col(FlightDataType.TYPE_LONGITUDE), alt = br.col(FlightDataType.TYPE_ALTITUDE),
				vz = br.col(FlightDataType.TYPE_VELOCITY_Z), v = br.col(FlightDataType.TYPE_VELOCITY_TOTAL);
		double launchAlt = sim.getOptions().getLaunchAltitude();
		double railAngle = sim.getOptions().getLaunchRodAngle();
		double t0 = time[0], tEnd = time[time.length - 1];
		double liftoff = first(b, FlightEvent.Type.LIFTOFF, t0), railClear = first(b, FlightEvent.Type.LAUNCHROD, liftoff);
		double ground = first(b, FlightEvent.Type.GROUND_HIT, Double.NaN), firstDeploy = first(b,
				FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT, Double.NaN);
		double lastThrust = Double.NaN;
		for (int i = 0; i < time.length; i++) {
			if (thrust[i] > 1e-6) {
				lastThrust = time[i];
			}
		}
		boolean startsOnPad = br.time.length > 0 && Math.abs(alt[0]) < 1;
		double pre = startsOnPad ? Math.max(0, padTime) : 0; // a separated stage starts in the air
		double post = Double.isNaN(ground) ? 0 : Math.max(0, restTime);
		double dt = 1 / rate;
		int n = (int) Math.floor((tEnd - t0 + pre + post) / dt) + 1;
		if (n > 2_000_000) {
			throw new ToolException("That is " + n + " samples; lower the rate (the flight lasts " + Units.num(tEnd - t0) + " s).");
		}
		double[] ot = new double[n], ax = new double[n], lat2 = new double[n], rr = new double[n], pr = new double[n],
				yr = new double[n], pp = new double[n], tc = new double[n], la = new double[n], lo = new double[n],
				am = new double[n], ag = new double[n], ovz = new double[n], osp = new double[n], om = new double[n];
		String[] phase = new String[n];
		double cos = Math.cos(railAngle), sin = Math.sin(railAngle);
		int j = 0;
		for (int k = 0; k < n; k++) {
			double ts = k * dt; // sensor clock: 0 at power-up on the pad
			double tf = ts - pre + t0; // flight clock
			ot[k] = ts;
			if (tf < t0 || (!Double.isNaN(ground) && tf > ground)) {
				boolean landed = tf > t0;
				int idx = landed ? time.length - 1 : 0;
				ax[k] = landed ? 0 : G * cos;
				lat2[k] = landed ? G : G * sin; // lying on its side after landing
				pp[k] = p[idx];
				tc[k] = temp[idx] - 273.15;
				la[k] = lat[idx]; // OpenRocket keeps latitude and longitude in degrees
				lo[k] = lon[idx];
				am[k] = alt[idx] + launchAlt;
				ag[k] = alt[idx];
				om[k] = 0;
				phase[k] = landed ? "landed" : "pad";
				continue;
			}
			while (j < time.length - 2 && time[j + 1] < tf) {
				j++;
			}
			double f = time[j + 1] > time[j] ? Math.max(0, Math.min(1, (tf - time[j]) / (time[j + 1] - time[j]))) : 0;
			double m = lerp(mass, j, f), th = lerp(thrust, j, f), dr = lerp(drag, j, f);
			double along = (th - (Double.isNaN(dr) ? 0 : dr)) / m;
			double machNow = lerp(mach, j, f), air = machNow * lerp(sos, j, f);
			double q = 0.5 * lerp(rho, j, f) * air * air;
			double cnNow = lerp(cn, j, f);
			double side = Double.isNaN(cnNow) || Double.isNaN(q) ? 0 : cnNow * q * lerp(aref, j, f) / m;
			if (tf < liftoff) {
				ax[k] = Math.max(along, G * cos);
				lat2[k] = G * sin;
				phase[k] = along > G * cos ? "rail" : "pad"; // thrust already beats the weight
			} else if (tf < railClear) {
				ax[k] = along;
				lat2[k] = G * sin;
				phase[k] = "rail";
			} else {
				ax[k] = along;
				lat2[k] = Double.isNaN(firstDeploy) || tf < firstDeploy ? side : 0;
				phase[k] = !Double.isNaN(lastThrust) && tf <= lastThrust ? "boost"
						: !Double.isNaN(firstDeploy) && tf >= firstDeploy ? "descent" : "coast";
			}
			rr[k] = Math.toDegrees(nz(lerp(roll, j, f)));
			pr[k] = Math.toDegrees(nz(lerp(pitch, j, f)));
			yr[k] = Math.toDegrees(nz(lerp(yaw, j, f)));
			pp[k] = lerp(p, j, f);
			tc[k] = lerp(temp, j, f) - 273.15;
			la[k] = lerp(lat, j, f);
			lo[k] = lerp(lon, j, f);
			ag[k] = lerp(alt, j, f);
			am[k] = ag[k] + launchAlt;
			ovz[k] = lerp(vz, j, f);
			osp[k] = lerp(v, j, f);
			om[k] = nz(machNow);
		}
		for (int k = 0; k < n; k++) { // gravity is not measured, so g's are g's
			ax[k] /= G;
			lat2[k] /= G;
		}
		List<double[]> evT = new ArrayList<>();
		List<String> evN = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			String name = switch (e.getType()) {
				case IGNITION -> "ignition";
				case LIFTOFF -> "liftoff";
				case LAUNCHROD -> "rail clear";
				case BURNOUT -> "burnout";
				case STAGE_SEPARATION -> "stage separation";
				case APOGEE -> "apogee";
				case RECOVERY_DEVICE_DEPLOYMENT -> "deployment" + (e.getSource() == null ? "" : ": " + e.getSource().getName());
				case GROUND_HIT -> "landing";
				default -> null;
			};
			if (name != null) {
				evT.add(new double[] { e.getTime() - t0 + pre, ag.length == 0 ? 0 : Branch.of(b).at(FlightDataType.TYPE_ALTITUDE, e.getTime()) });
				evN.add(name);
			}
		}
		return new Trace(ot, ax, lat2, rr, pr, yr, pp, tc, la, lo, am, ag, ovz, osp, om, phase, evT, evN, pre);
	}

	private static double first(FlightDataBranch b, FlightEvent.Type type, double fallback) {
		FlightEvent e = b.getFirstEvent(type);
		return e == null ? fallback : e.getTime();
	}

	private static double lerp(double[] a, int j, double f) {
		if (j + 1 >= a.length) {
			return a[a.length - 1];
		}
		return a[j] + f * (a[j + 1] - a[j]);
	}

	private static double nz(double v) {
		return Double.isNaN(v) ? 0 : v;
	}

	/** A measured value: Gaussian noise, then rounding to the resolution, then clipping to the range. */
	static double measure(double truth, Spec s, Random rnd) {
		double v = truth + (s.noise() > 0 ? rnd.nextGaussian() * s.noise() : 0);
		if (s.resolution() > 0) {
			v = Math.round(v / s.resolution()) * s.resolution();
		}
		if (!Double.isNaN(s.range())) {
			v = Math.max(-s.range(), Math.min(s.range(), v));
		}
		if (!Double.isNaN(s.min())) {
			v = Math.max(s.min(), v);
		}
		if (!Double.isNaN(s.max())) {
			v = Math.min(s.max(), v);
		}
		return v;
	}

	/** GPS receivers stop giving fixes above these (the export-control "COCOM" limits). */
	static final double GPS_SPEED_LIMIT = 515, GPS_ALTITUDE_LIMIT = 18000;

	/** Writes the CSV (and an events CSV beside it); returns {rows, columns}. */
	public static int[] write(Trace tr, List<Spec> specs, Path csv, double gpsRate, long seed, boolean truth) throws IOException {
		Random rnd = new Random(seed);
		List<String> cols = new ArrayList<>(List.of("time_s"));
		for (Spec s : specs) {
			switch (s.type()) {
				case "accelerometer" -> cols.addAll(List.of(s.id() + "_x_g", s.id() + "_y_g", s.id() + "_z_g"));
				case "gyroscope" -> cols.addAll(List.of(s.id() + "_x_dps", s.id() + "_y_dps", s.id() + "_z_dps"));
				case "barometer" -> cols.addAll(List.of(s.id() + "_pressure_pa", s.id() + "_temperature_c"));
				case "gps" -> cols.addAll(List.of(s.id() + "_fix", s.id() + "_lat_deg", s.id() + "_lon_deg", s.id() + "_alt_m"));
				default -> {
				}
			}
		}
		if (truth) {
			cols.addAll(List.of("truth_altitude_agl_m", "truth_vertical_velocity_mps", "truth_speed_mps", "truth_mach",
					"truth_axial_g", "truth_phase"));
		}
		Path abs = csv.toAbsolutePath();
		if (abs.getParent() != null) {
			Files.createDirectories(abs.getParent());
		}
		int every = Math.max(1, (int) Math.round(1 / (gpsRate * (tr.t().length > 1 ? tr.t()[1] - tr.t()[0] : 1))));
		try (BufferedWriter w = Files.newBufferedWriter(abs)) {
			w.write(String.join(",", cols));
			w.newLine();
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < tr.size(); k++) {
				sb.setLength(0);
				sb.append(f(tr.t()[k], 4));
				for (Spec s : specs) {
					switch (s.type()) {
						case "accelerometer" -> sb.append(',').append(f(measure(tr.axial()[k], s, rnd), 5)).append(',')
								.append(f(measure(tr.lateral()[k], s, rnd), 5)).append(',').append(f(measure(0, s, rnd), 5));
						case "gyroscope" -> sb.append(',').append(f(measure(tr.roll()[k], s, rnd), 4)).append(',')
								.append(f(measure(tr.pitch()[k], s, rnd), 4)).append(',').append(f(measure(tr.yaw()[k], s, rnd), 4));
						case "barometer" -> sb.append(',').append(f(measure(tr.pressure()[k], s, rnd), 2)).append(',')
								.append(f(tr.temperature()[k] + rnd.nextGaussian() * 0.1, 2));
						case "gps" -> {
							boolean sample = k % every == 0;
							boolean lock = tr.speed()[k] < GPS_SPEED_LIMIT && tr.altMsl()[k] < GPS_ALTITUDE_LIMIT;
							if (!sample) {
								sb.append(",,,,");
							} else if (!lock) {
								sb.append(",0,,,");
							} else {
								double h = Double.isNaN(s.noise()) ? 2.5 : Math.max(0, s.noise());
								double north = rnd.nextGaussian() * h, east = rnd.nextGaussian() * h;
								double la = tr.lat()[k] + Math.toDegrees(north / 6_371_000.0);
								double lo = tr.lon()[k]
										+ Math.toDegrees(east / (6_371_000.0 * Math.max(0.01, Math.cos(Math.toRadians(tr.lat()[k])))));
								sb.append(",1,").append(f(la, 7)).append(',').append(f(lo, 7)).append(',')
										.append(f(tr.altMsl()[k] + rnd.nextGaussian() * 2 * h, 1));
							}
						}
						default -> {
						}
					}
				}
				if (truth) {
					sb.append(',').append(f(tr.altAgl()[k], 2)).append(',').append(f(tr.vz()[k], 3)).append(',')
							.append(f(tr.speed()[k], 3)).append(',').append(f(tr.mach()[k], 4)).append(',')
							.append(f(tr.axial()[k], 4)).append(',').append(tr.phase()[k]);
				}
				w.write(sb.toString());
				w.newLine();
			}
		}
		Path events = eventsPath(abs);
		try (BufferedWriter w = Files.newBufferedWriter(events)) {
			w.write("time_s,event,altitude_agl_m");
			w.newLine();
			for (int i = 0; i < tr.eventNames().size(); i++) {
				w.write(f(tr.eventTimes().get(i)[0], 3) + "," + tr.eventNames().get(i).replace(',', ';') + ","
						+ f(tr.eventTimes().get(i)[1], 1));
				w.newLine();
			}
		}
		return new int[] { tr.size(), cols.size() };
	}

	public static Path eventsPath(Path csv) {
		String n = csv.getFileName().toString();
		int dot = n.lastIndexOf('.');
		return csv.resolveSibling((dot > 0 ? n.substring(0, dot) : n) + "-events.csv");
	}

	private static String f(double v, int digits) {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			return "";
		}
		return String.format(Locale.ROOT, "%." + digits + "f", v);
	}

	/** Peak of |values| and when it happens, over the samples where {@code use} holds. */
	record Peak(double value, double time) {
	}

	static Peak peakAbs(double[] v, double[] t) {
		double best = 0, when = 0;
		for (int k = 0; k < v.length; k++) {
			if (Math.abs(v[k]) > best) {
				best = Math.abs(v[k]);
				when = t[k];
			}
		}
		return new Peak(best, when);
	}

	/** {first, last} sensor time where Mach >= {@code mach} (NaN when never). */
	public static double[] baroWindow(Trace tr, double mach) {
		double a = Double.NaN, b = Double.NaN;
		for (int k = 0; k < tr.size(); k++) {
			if (tr.mach()[k] >= mach && !"descent".equals(tr.phase()[k])) {
				if (Double.isNaN(a)) {
					a = tr.t()[k];
				}
				b = tr.t()[k];
			}
		}
		return new double[] { a, b };
	}

	/** Each sensor's range against the flight's peaks: rows {sensor, quantity, peak, range, use, status, note}. */
	public static List<Map<String, Object>> check(Trace tr, List<Spec> specs, double baroMach) {
		List<Map<String, Object>> rows = new ArrayList<>();
		double[] combined = new double[tr.size()];
		for (int k = 0; k < tr.size(); k++) {
			combined[k] = Math.hypot(tr.axial()[k], tr.lateral()[k]);
		}
		for (Spec s : specs) {
			switch (s.type()) {
				case "accelerometer" -> {
					double[] up = new double[tr.size()], down = new double[tr.size()];
					for (int k = 0; k < tr.size(); k++) {
						boolean descent = "descent".equals(tr.phase()[k]) || "landed".equals(tr.phase()[k]);
						up[k] = descent ? 0 : tr.axial()[k];
						down[k] = descent ? tr.axial()[k] : 0;
					}
					Peak ax = peakAbs(up, tr.t()), lat = peakAbs(tr.lateral(), tr.t()), open = peakAbs(down, tr.t());
					rows.add(row(s, "axial acceleration (x), boost and coast", ax, s.range(), "g", phaseAt(tr, ax.time())));
					rows.add(row(s, "lateral acceleration (y, z)", lat, s.range(), "g", phaseAt(tr, lat.time())));
					if (open.value() > 0) {
						Map<String, Object> r = row(s, "parachute openings", open, s.range(), "g", null);
						if ("FAIL".equals(r.get("status")) || "WARN".equals(r.get("status"))) {
							r.put("status", "WARN");
							r.put("note", "OpenRocket opens canopies instantly, so this is an upper bound; real openings spread "
									+ "over a fraction of a second (opening_shock gives the load). A clipped reading here only "
									+ "matters if your software uses it");
						}
						rows.add(r);
					}
				}
				case "gyroscope" -> {
					rows.add(row(s, "roll rate (x)", peakAbs(tr.roll(), tr.t()), s.range(), "deg/s", null));
					Peak py = peakAbs(tr.pitch(), tr.t()), yw = peakAbs(tr.yaw(), tr.t());
					rows.add(row(s, "pitch / yaw rate (y, z)", py.value() >= yw.value() ? py : yw, s.range(), "deg/s", null));
				}
				case "barometer" -> {
					double minP = Double.POSITIVE_INFINITY, when = 0;
					double maxP = 0;
					for (int k = 0; k < tr.size(); k++) {
						if (tr.pressure()[k] < minP) {
							minP = tr.pressure()[k];
							when = tr.t()[k];
						}
						maxP = Math.max(maxP, tr.pressure()[k]);
					}
					Map<String, Object> r = new LinkedHashMap<>();
					r.put("sensor", s.name());
					r.put("quantity", "pressure (lowest at apogee)");
					r.put("peak", Units.fmt(minP, Dim.PRESSURE) + " at t=" + Units.num(when) + " s");
					r.put("range", (Double.isNaN(s.min()) ? "?" : Units.fmt(s.min(), Dim.PRESSURE)) + " to "
							+ (Double.isNaN(s.max()) ? "?" : Units.fmt(s.max(), Dim.PRESSURE)));
					boolean low = !Double.isNaN(s.min()) && minP < s.min(), high = !Double.isNaN(s.max()) && maxP > s.max();
					r.put("status", low || high ? "FAIL" : !Double.isNaN(s.min()) && minP < s.min() * 1.1 ? "WARN" : "PASS");
					if (low) {
						r.put("note", "below the sensor's range above " + Units.fmt(altitudeAt(s.min()), Dim.DISTANCE)
								+ " MSL (standard atmosphere): apogee by barometer is lost; use a wider-range sensor (e.g. MS5611, "
								+ "1 kPa) or detect apogee by integration");
					}
					rows.add(r);
					double[] w = baroWindow(tr, baroMach);
					if (!Double.isNaN(w[0])) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("sensor", s.name());
						m.put("quantity", "static pressure above Mach " + Units.num(baroMach));
						m.put("peak", "t=" + Units.num(w[0]) + " to " + Units.num(w[1]) + " s (max Mach "
								+ Units.num(max(tr.mach())) + ")");
						m.put("status", "WARN");
						m.put("note", "ports read wrong near Mach 1 (a false 'apogee' as the shock passes): lock out barometric "
								+ "events until " + Units.num(Math.ceil(w[1] - tr.padTime() + 1)) + " s after launch (see "
								+ "altimeter_settings)");
						rows.add(m);
					}
				}
				case "gps" -> {
					double vmax = max(tr.speed()), hmax = max(tr.altMsl());
					double lost0 = Double.NaN, lost1 = Double.NaN;
					for (int k = 0; k < tr.size(); k++) {
						if (tr.speed()[k] >= GPS_SPEED_LIMIT || tr.altMsl()[k] >= GPS_ALTITUDE_LIMIT) {
							if (Double.isNaN(lost0)) {
								lost0 = tr.t()[k];
							}
							lost1 = tr.t()[k];
						}
					}
					Map<String, Object> r = new LinkedHashMap<>();
					r.put("sensor", s.name());
					r.put("quantity", "speed and altitude (export limits 515 m/s, 18 km)");
					r.put("peak", Units.fmt(vmax, Dim.VELOCITY) + ", " + Units.fmt(hmax, Dim.DISTANCE) + " MSL");
					r.put("status", Double.isNaN(lost0) ? "PASS" : "WARN");
					if (!Double.isNaN(lost0)) {
						r.put("note", "no fix from t=" + Units.num(lost0) + " to " + Units.num(lost1)
								+ " s; most receivers recover within seconds, some need a restart: check yours");
					}
					rows.add(r);
				}
				default -> {
				}
			}
		}
		return rows;
	}

	private static String phaseAt(Trace tr, double time) {
		for (int k = 0; k < tr.size(); k++) {
			if (tr.t()[k] >= time) {
				return tr.phase()[k];
			}
		}
		return null;
	}

	private static Map<String, Object> row(Spec s, String quantity, Peak peak, double range, String unit, String phase) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("sensor", s.name());
		r.put("quantity", quantity);
		r.put("peak", Units.num(peak.value()) + " " + unit + " at t=" + Units.num(peak.time()) + " s" + (phase == null ? "" : " (" + phase + ")"));
		if (Double.isNaN(range)) {
			r.put("range", "not given");
			r.put("status", "INFO");
			return r;
		}
		double use = peak.value() / range;
		r.put("range", "±" + Units.num(range) + " " + unit);
		r.put("use", Units.num(use * 100) + "% of range");
		r.put("status", use > 1 ? "FAIL" : use > 0.8 ? "WARN" : "PASS");
		if (use > 1) {
			r.put("note", "saturates: the reading clips at ±" + Units.num(range) + " " + unit
					+ (unit.equals("g") ? "; add a high-g accelerometer (e.g. ±200 g) for boost" : ""));
		} else if (use > 0.8) {
			r.put("note", "little headroom for a hotter motor, gusts or vibration");
		}
		return r;
	}

	private static double max(double[] v) {
		double m = 0;
		for (double x : v) {
			m = Math.max(m, x);
		}
		return m;
	}

	/** Standard-atmosphere altitude of a pressure (inverse of Atmosphere.at, by bisection). */
	static double altitudeAt(double pressure) {
		double lo = -500, hi = 20000;
		for (int i = 0; i < 60; i++) {
			double mid = 0.5 * (lo + hi);
			if (Atmosphere.at(mid).pressure() > pressure) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return 0.5 * (lo + hi);
	}
}
