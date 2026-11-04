package io.github.openrocketmcp.or;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.report.Png;
import io.github.openrocketmcp.report.Svg;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.UnitSystem;
import io.github.openrocketmcp.units.Units;

/**
 * Flight logs (altimeter CSV: time, altitude) against the simulation: apogee, time to apogee and descent rates, and a
 * drag multiplier that makes OpenRocket reproduce the measured apogee (calibration for the next flight).
 */
public final class FlightLog {
	private FlightLog() {
	}

	/**
	 * Time (s from the first sample) and altitude above the pad (m); with an accelerometer column, also its time (same
	 * origin) and the specific force along the rocket's axis (m/s2, reading +1 g on the pad), else null.
	 */
	public record Log(double[] t, double[] alt, String note, double[] accT, double[] acc) {
		public Log(double[] t, double[] alt, String note) {
			this(t, alt, note, null, null);
		}

		boolean hasAccel() {
			return acc != null && acc.length >= 50;
		}
	}

	/**
	 * Parses an altimeter CSV. Columns are found by header ("time", "alt"); without a header the first two columns
	 * are time and altitude. Units come from the header ("(ft)", "(m)", "(ms)") or {@code altUnit} ("ft", "m", or
	 * null = auto, feet when unknown). The pad altitude (median of the first samples) is subtracted.
	 */
	public static Log parse(String text, String altUnit) {
		String[] lines = text.split("\\r?\\n");
		int iT = 0, iA = 1, iAcc = -1, iTAcc = -1;
		double tScale = 1, aScale = Double.NaN, accScale = G, tAccScale = 1;
		List<double[]> rows = new ArrayList<>();
		List<double[]> accRows = new ArrayList<>();
		String note = "";
		boolean headerSeen = false;
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			String[] f = line.split("[,;\\t]");
			if (!headerSeen && rows.isEmpty() && !numeric(f[0])) {
				for (int i = 0; i < f.length; i++) {
					String k = f[i].toLowerCase(Locale.ROOT);
					boolean accName = k.contains("accel") || k.matches(".*\\bacc\\b.*");
					if (k.contains("time") || k.trim().equals("t") || k.contains("sec")) {
						double ts = k.contains("ms") || k.contains("milli") ? 0.001 : 1;
						if (accName) { // a separate clock for the accelerometer, e.g. "Time-Axial Accel (Gs)"
							iTAcc = i;
							tAccScale = ts;
						} else {
							iT = i;
							tScale = ts;
						}
					} else if (accName) {
						if (iAcc < 0 || k.contains("axial") || k.contains("vert")) {
							iAcc = i;
							accScale = k.contains("ft/s") ? 0.3048 : k.contains("m/s") ? 1 : G;
						}
					} else if (k.contains("alt") || k.contains("height") || k.contains("agl")) {
						iA = i;
						if (k.contains("ft") || k.contains("feet")) {
							aScale = 0.3048;
						} else if (k.contains("(m)") || k.contains("[m]") || k.contains("meter") || k.contains("metre")) {
							aScale = 1;
						}
					}
				}
				headerSeen = true;
				continue;
			}
			try {
				rows.add(new double[] { Double.parseDouble(f[iT].trim()) * tScale, Double.parseDouble(f[iA].trim()) });
			} catch (RuntimeException e) {
				// comment or status line inside the log
			}
			if (iAcc >= 0) {
				int it = iTAcc >= 0 ? iTAcc : iT;
				try {
					accRows.add(new double[] { Double.parseDouble(f[it].trim()) * (iTAcc >= 0 ? tAccScale : tScale),
							Double.parseDouble(f[iAcc].trim()) * accScale });
				} catch (RuntimeException e) {
					// no accelerometer sample on this row
				}
			}
		}
		if (rows.size() < 10) {
			throw new ToolException("The flight log needs at least 10 numeric rows of time and altitude.");
		}
		if (altUnit != null && !altUnit.isBlank()) {
			aScale = Units.toSi("1 " + altUnit.trim(), Dim.DISTANCE);
		} else if (Double.isNaN(aScale)) {
			aScale = 0.3048;
			note = "Altitude unit not in the header; assumed feet (pass altitudeUnit to override).";
		}
		rows.sort((a, b) -> Double.compare(a[0], b[0]));
		int n = rows.size();
		double[] t = new double[n], a = new double[n];
		// Pad altitude: median of the samples before the reading leaves the first one by more than ~2 m.
		double a0 = rows.get(0)[1];
		double tol = 2 / aScale;
		List<Double> padSamples = new ArrayList<>();
		for (double[] r : rows) {
			if (Math.abs(r[1] - a0) > tol) {
				break;
			}
			padSamples.add(r[1]);
		}
		double[] first = padSamples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		double pad = first[first.length / 2];
		for (int i = 0; i < n; i++) {
			t[i] = rows.get(i)[0] - rows.get(0)[0];
			a[i] = (rows.get(i)[1] - pad) * aScale;
		}
		boolean ms = t[n - 1] > 3000; // milliseconds without a header
		if (ms) {
			for (int i = 0; i < n; i++) {
				t[i] /= 1000;
			}
		}
		double[][] accel = accel(accRows, rows.get(0)[0], ms && iTAcc < 0 ? 0.001 : 1);
		return new Log(t, a, note, accel == null ? null : accel[0], accel == null ? null : accel[1]);
	}

	static final double G = 9.80665;

	/**
	 * Accelerometer samples as time (origin {@code t0}) and specific force along the axis: the pad reading (median of
	 * the first samples) is taken as +1 g, which removes the sensor's offset, and a sensor mounted the other way round
	 * (boost reading below the pad) is turned over. Null when there are too few samples.
	 */
	static double[][] accel(List<double[]> rows, double t0, double tScale) {
		if (rows.size() < 50) {
			return null;
		}
		rows.sort((a, b) -> Double.compare(a[0], b[0]));
		int n = rows.size();
		double[] first = new double[Math.max(5, Math.min(40, n / 20))];
		for (int i = 0; i < first.length; i++) {
			first[i] = rows.get(i)[1];
		}
		java.util.Arrays.sort(first);
		double pad = first[first.length / 2], hi = -Double.MAX_VALUE, lo = Double.MAX_VALUE;
		for (double[] r : rows) {
			hi = Math.max(hi, r[1] - pad);
			lo = Math.min(lo, r[1] - pad);
		}
		double sign = -lo > hi ? -1 : 1;
		double[] t = new double[n], f = new double[n];
		for (int i = 0; i < n; i++) {
			t[i] = (rows.get(i)[0] - t0) * tScale;
			f[i] = sign * (rows.get(i)[1] - pad) + G;
		}
		return new double[][] { t, f };
	}

	private static boolean numeric(String s) {
		try {
			Double.parseDouble(s.trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * Key numbers of a trajectory. {@code launch} is the first crossing of 10 m; the caller turns it into time from
	 * lift-off with the simulation's own time to climb 10 m. The main band runs from 35% of the main's deployment
	 * altitude (past the canopy opening and the swing that follows) down to 5 m; without a known main altitude, from
	 * 150 m (or 30% of apogee) to 20 m.
	 */
	record Metrics(double launch, double apogee, double timeToApogee, double upperDescent, double lowerDescent) {
	}

	static Metrics metrics(double[] t, double[] alt) {
		return metrics(t, alt, Double.NaN);
	}

	static Metrics metrics(double[] t, double[] alt, double mainAltitude) {
		int ia = 0;
		for (int i = 1; i < alt.length; i++) {
			if (alt[i] > alt[ia]) {
				ia = i;
			}
		}
		double launch = Double.NaN;
		for (int i = 0; i <= ia; i++) {
			if (alt[i] > 10) {
				launch = i == 0 ? t[0] : t[i - 1] + (t[i] - t[i - 1]) * (10 - alt[i - 1]) / (alt[i] - alt[i - 1]);
				break;
			}
		}
		double apo = alt[ia];
		double[] main = mainBand(apo, mainAltitude);
		return new Metrics(launch, apo, apogeeTime(t, alt, ia) - launch, rate(t, alt, ia, 0.9 * apo, 0.6 * apo),
				rate(t, alt, ia, main[0], main[1]));
	}

	/**
	 * Time of apogee from a parabola through the samples within 1.5 s of the highest one: the top of a flight is flat,
	 * so the highest raw sample of a noisy barometric log can sit half a second either side of the real apogee.
	 */
	static double apogeeTime(double[] t, double[] alt, int ia) {
		double tc = t[ia];
		double[] sx = new double[5], sy = new double[3];
		int n = 0;
		for (int i = 0; i < t.length; i++) {
			double x = t[i] - tc;
			if (Math.abs(x) <= 1.5) {
				double xk = 1;
				for (int k = 0; k < 5; k++) {
					sx[k] += xk;
					if (k < 3) {
						sy[k] += alt[i] * xk;
					}
					xk *= x;
				}
				n++;
			}
		}
		if (n < 5) {
			return tc;
		}
		double[][] m = { { sx[0], sx[1], sx[2] }, { sx[1], sx[2], sx[3] }, { sx[2], sx[3], sx[4] } };
		double d = det(m);
		if (d == 0) {
			return tc;
		}
		double[] c = new double[3];
		for (int j = 0; j < 3; j++) {
			double[][] mj = { m[0].clone(), m[1].clone(), m[2].clone() };
			for (int r = 0; r < 3; r++) {
				mj[r][j] = sy[r];
			}
			c[j] = det(mj) / d;
		}
		double vertex = c[2] < 0 ? -c[1] / (2 * c[2]) : Double.NaN;
		return Double.isFinite(vertex) && Math.abs(vertex) <= 1.5 ? tc + vertex : tc;
	}

	private static double det(double[][] m) {
		return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
				+ m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
	}

	/** Upper and lower altitude of the band where the main's steady descent rate is read. */
	static double[] mainBand(double apogee, double mainAltitude) {
		if (Double.isFinite(mainAltitude) && mainAltitude > 30 && mainAltitude < 0.9 * apogee) {
			return new double[] { 0.35 * mainAltitude, 5 };
		}
		return new double[] { Math.min(150, 0.3 * apogee), 20 };
	}

	/** Altitude of the last recovery deployment after apogee (the main on a dual deploy), NaN on single deploy. */
	static double mainDeploymentAltitude(Simulation sim, double[] t, double[] alt) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		double apogeeTime = Double.NaN;
		List<Double> deployments = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() == FlightEvent.Type.APOGEE && Double.isNaN(apogeeTime)) {
				apogeeTime = e.getTime();
			} else if (e.getType() == FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT) {
				deployments.add(e.getTime());
			}
		}
		if (deployments.size() < 2 || Double.isNaN(apogeeTime)) {
			return Double.NaN;
		}
		double last = deployments.get(deployments.size() - 1);
		Double h = last > apogeeTime + 1 ? interpolate(t, alt, last) : null;
		return h == null ? Double.NaN : h;
	}

	/** Mean descent rate between two altitudes after apogee (least-squares slope), NaN when not covered. */
	static double rate(double[] t, double[] alt, int ia, double hi, double lo) {
		double sx = 0, sy = 0, sxx = 0, sxy = 0;
		int n = 0;
		for (int i = ia; i < t.length; i++) {
			if (alt[i] <= hi && alt[i] >= lo) {
				sx += t[i];
				sy += alt[i];
				sxx += t[i] * t[i];
				sxy += t[i] * alt[i];
				n++;
			}
		}
		if (n < 3) {
			return Double.NaN;
		}
		double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
		return -slope;
	}

	static double[][] simTrack(Simulation sim) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		return new double[][] { br.time, br.col(FlightDataType.TYPE_ALTITUDE) };
	}

	/** Compares the log with the simulation and fits a drag multiplier to the measured apogee. */
	public static Map<String, Object> compare(Simulation base, OpenRocketDocument doc, Log log, String svgPath,
			String title) throws IOException {
		return compare(base, doc, log, svgPath, title, false);
	}

	/**
	 * As above; with {@code series} the result also carries both altitude curves on one time grid from launch (SI,
	 * "series": time, simulated, measured) for an app to plot.
	 */
	public static Map<String, Object> compare(Simulation base, OpenRocketDocument doc, Log log, String svgPath,
			String title, boolean series) throws IOException {
		double[] factors = { 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.35, 1.5, 1.75, 2.0 };
		List<Simulation> sims = new ArrayList<>();
		for (double f : factors) {
			Simulation v = Variants.of(base, doc, null, null);
			if (f != 1) {
				Variants.listen(v, new MonteCarlo.Scaling(f, 1));
			}
			sims.add(v);
		}
		List<Variants.Run> runs = Variants.runAll(sims);
		double[] apogees = new double[factors.length];
		Simulation nominal = null;
		for (int i = 0; i < runs.size(); i++) {
			if (!runs.get(i).ok()) {
				throw new ToolException("Simulation failed: " + runs.get(i).error());
			}
			apogees[i] = runs.get(i).sim().getSimulatedData().getMaxAltitude();
			if (factors[i] == 1) {
				nominal = runs.get(i).sim();
			}
		}
		double[][] st = simTrack(nominal);
		double mainAlt = mainDeploymentAltitude(nominal, st[0], st[1]);
		Metrics s = metrics(st[0], st[1], mainAlt);
		// Time from lift-off: the log's 10 m crossing less the simulation's time to climb those 10 m.
		Metrics m0 = metrics(log.t(), log.alt(), mainAlt);
		double climb = Double.isNaN(s.launch()) ? 0 : s.launch() - st[0][0];
		Metrics m = new Metrics(m0.launch(), m0.apogee(), m0.timeToApogee() + climb, m0.upperDescent(), m0.lowerDescent());
		s = new Metrics(s.launch(), s.apogee(), s.timeToApogee() + climb, s.upperDescent(), s.lowerDescent());
		double[] band = mainBand(Math.max(m.apogee(), s.apogee()), mainAlt);
		String mainLabel = Double.isFinite(mainAlt) && band[1] == 5
				? "descentRate under main (35% of its deployment altitude to 5 m)"
				: "descentRate last 150 m (main)";

		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> rows = new ArrayList<>();
		rows.add(row("apogee", m.apogee(), s.apogee(), Dim.DISTANCE));
		rows.add(row("timeToApogee", m.timeToApogee(), s.timeToApogee(), Dim.TIME));
		rows.add(row("descentRate 90%-60% of apogee (drogue)", m.upperDescent(), s.upperDescent(), Dim.VELOCITY));
		rows.add(row(mainLabel, m.lowerDescent(), s.lowerDescent(), Dim.VELOCITY));
		out.put("comparison", rows);

		// Apogee decreases monotonically with the drag factor: interpolate the factor matching the measured apogee.
		double fit = Double.NaN;
		for (int i = 1; i < factors.length; i++) {
			double a0 = apogees[i - 1], a1 = apogees[i];
			if ((m.apogee() <= a0 && m.apogee() >= a1) || (m.apogee() >= a0 && m.apogee() <= a1)) {
				fit = factors[i - 1] + (m.apogee() - a0) / (a1 - a0) * (factors[i] - factors[i - 1]);
				break;
			}
		}
		Map<String, Object> cal = new LinkedHashMap<>();
		if (Double.isNaN(fit)) {
			cal.put("dragFactor", "outside 0.5-2.0: the apogee difference is not a drag effect alone (check motor, mass, "
					+ "altimeter units and the launch angle / wind used in the simulation)");
		} else {
			cal.put("dragFactor", Units.num(fit) + " x OpenRocket's airframe drag reproduces the measured apogee");
			cal.put("use", "Monte Carlo around it, or an imported drag table scaled by it; if the motor under-performed, "
					+ "thrust is the more likely cause (compare time to apogee and the boost phase)");
		}
		out.put("calibration", cal);
		Boost boost = null;
		List<Svg.Series> climbCurves = new ArrayList<>();
		if (log.hasAccel()) {
			boost = boost(log, nominal);
			if (boost != null) {
				out.put("accelerometer", boostReport(boost, base, doc, log, m, s, fit, climbCurves));
			}
		}
		List<String> notes = new ArrayList<>();
		if (!log.note().isEmpty()) {
			notes.add(log.note());
		}
		notes.add("Match the simulation to the day: wind, launch angle, site altitude and temperature (run with overrides), "
				+ "and the as-flown mass (edit or override masses) before reading the drag factor.");
		notes.add("Barometric altimeters read low near Mach 1 and lag at apogee; the drogue and main bands are fitted slopes, "
				+ "time to apogee counts from lift-off (the log's 10 m crossing less the simulated time to climb 10 m) to the top of "
				+ "a parabola through the samples around apogee.");
		out.put("notes", notes);

		double shift = m.launch() - s.launch(); // align launches
		double[] lt = new double[log.t().length];
		for (int i = 0; i < lt.length; i++) {
			lt[i] = log.t()[i] - shift;
		}
		if (series) {
			out.put("series", grid(st[0], st[1], lt, log.alt(), 1000));
			List<Map<String, Object>> si = new ArrayList<>();
			si.add(si("Apogee", m.apogee(), s.apogee(), Dim.DISTANCE));
			si.add(si("Time to apogee", m.timeToApogee(), s.timeToApogee(), Dim.TIME));
			si.add(si("Descent rate, drogue (90% to 60% of apogee)", m.upperDescent(), s.upperDescent(), Dim.VELOCITY));
			si.add(si(band[1] == 5 ? "Descent rate, main (35% of its deployment altitude to 5 m)"
					: "Descent rate, main (last 150 m)", m.lowerDescent(), s.lowerDescent(), Dim.VELOCITY));
			out.put("valuesSI", si);
			out.put("dragFactorFit", Double.isNaN(fit) ? null : fit);
		}
		if (svgPath != null) {
			Path p = Path.of(svgPath).toAbsolutePath();
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			boolean imperial = Units.system() == UnitSystem.IMPERIAL;
			double k = imperial ? 1 / 0.3048 : 1;
			Files.writeString(p, Svg.lines(title, "Time (s)",
					"Altitude (" + (imperial ? "ft" : "m") + ")",
					List.of(new Svg.Series("simulated", st[0], scale(st[1], k)),
							new Svg.Series("measured", lt, scale(log.alt(), k))),
					Double.NaN, null, List.of()));
			Png.attachFile(p, "Simulated and measured altitude");
			out.put("plot", p.toString());
			if (boost != null) {
				Path q = p.resolveSibling(p.getFileName().toString().replaceFirst("(\\.svg)?$", "-accel.svg"));
				Files.writeString(q, Svg.lines(title + ": acceleration on the boost", "Time from motor start (s)",
						"Axial acceleration (g)",
						List.of(new Svg.Series("simulated", boost.simT(), boost.simG()),
								new Svg.Series("measured", boost.measT(), boost.measG())),
						0, "0 g", List.of()));
				Png.attachFile(q, "Simulated and measured axial acceleration");
				out.put("accelPlot", q.toString());
				if (!climbCurves.isEmpty()) {
					// The climb (lift-off to just past apogee) of the measured flight and each version of the simulation.
					double tEnd = s.launch() + s.timeToApogee() + 3;
					List<Svg.Series> cs = new ArrayList<>();
					cs.add(clip(new Svg.Series("measured", lt, scale(log.alt(), k)), tEnd));
					for (Svg.Series c : climbCurves) {
						cs.add(clip(new Svg.Series(c.name(), c.x(), scale(c.y(), k)), tEnd));
					}
					// measured last so it draws on top; keep its colour by order
					Path cp = p.resolveSibling(p.getFileName().toString().replaceFirst("(\\.svg)?$", "-climb.svg"));
					Files.writeString(cp, Svg.lines(title + ": the climb", "Time (s)", "Altitude (" + (imperial ? "ft" : "m") + ")",
							cs, Double.NaN, null, List.of()));
					Png.attachFile(cp, "The climb: measured and three versions of the simulation");
					out.put("climbPlot", cp.toString());
				}
			}
		}
		return out;
	}

	/**
	 * What the accelerometer says about the boost and the start of the coast, beside the simulation. Velocities come
	 * from integrating the specific force less g (vertical flight); the coast drag ratio compares drag / mass / v^2.
	 */
	record Boost(double burnTime, double simBurnTime, double vBurnout, double simVBurnout, double coastFrom, double coastTo,
			double dragRatio, double[] measT, double[] measG, double[] simT, double[] simG) {
	}

	static Boost boost(Log log, Simulation sim) {
		double[] t = log.accT(), f = log.acc();
		int start = -1;
		for (int i = 0; i < f.length; i++) {
			if (f[i] > 2 * G) {
				start = i;
				break;
			}
		}
		if (start < 1) {
			return null;
		}
		int peak = start;
		for (int i = start; i < f.length && t[i] < t[start] + 1; i++) {
			peak = f[i] > f[peak] ? i : peak;
		}
		int burnout = -1;
		for (int i = peak; i < f.length; i++) {
			if (f[i] < 0) {
				burnout = i;
				break;
			}
		}
		if (burnout < 0) {
			return null;
		}
		double v = 0, vBurn = 0, coastFrom = t[burnout] + 0.3, coastTo = Math.min(t[burnout] + 2.0, t[t.length - 1]);
		double kSum = 0;
		int kN = 0;
		for (int i = start; i < f.length - 1 && t[i] <= coastTo; i++) {
			double a = f[i] - G;
			v = Math.max(0, v + a * (t[i + 1] - t[i]));
			if (i == burnout) {
				vBurn = v;
			}
			if (t[i] >= coastFrom && v > 20) {
				kSum += -f[i] / (v * v);
				kN++;
			}
		}
		Branch br = Branch.of(sim.getSimulatedData().getBranch(0));
		double[] st = br.time, thrust = br.col(FlightDataType.TYPE_THRUST_FORCE), drag = br.col(FlightDataType.TYPE_DRAG_FORCE),
				mass = br.col(FlightDataType.TYPE_MASS), vel = br.col(FlightDataType.TYPE_VELOCITY_TOTAL);
		double simBurn = Double.NaN;
		for (FlightEvent e : sim.getSimulatedData().getBranch(0).getEvents()) {
			if (e.getType() == FlightEvent.Type.BURNOUT) {
				simBurn = e.getTime();
				break;
			}
		}
		if (Double.isNaN(simBurn)) {
			return null;
		}
		double tBurnMeas = t[burnout] - t[start];
		double sk = 0;
		int sn = 0;
		List<Double> sx = new ArrayList<>(), sy = new ArrayList<>();
		for (int i = 0; i < st.length; i++) {
			double fs = (thrust[i] - drag[i]) / mass[i];
			if (st[i] <= simBurn + (coastTo - t[burnout]) + 0.5) {
				sx.add(st[i]);
				sy.add(fs / G);
			}
			double rel = st[i] - simBurn;
			if (rel >= coastFrom - t[burnout] && rel <= coastTo - t[burnout] && vel[i] > 20) {
				sk += (drag[i] / mass[i]) / (vel[i] * vel[i]);
				sn++;
			}
		}
		Double vSim = interpolate(st, vel, simBurn);
		if (kN < 5 || sn < 3 || vSim == null) {
			return null;
		}
		List<Double> mx = new ArrayList<>(), my = new ArrayList<>();
		for (int i = 0; i < f.length && t[i] <= coastTo; i++) {
			if (t[i] >= t[start] - 0.2) {
				mx.add(t[i] - t[start]);
				my.add(f[i] / G);
			}
		}
		return new Boost(tBurnMeas, simBurn, vBurn, vSim, coastFrom - t[burnout], coastTo - t[burnout],
				(kSum / kN) / (sk / sn), arr(mx), arr(my), arr(sx), arr(sy));
	}

	private static double[] arr(List<Double> l) {
		double[] o = new double[l.size()];
		for (int i = 0; i < o.length; i++) {
			o[i] = l.get(i);
		}
		return o;
	}

	/**
	 * The boost and coast against the simulation, then a two-step fit: the thrust factor that gives the measured
	 * burnout speed (with the drag the coast measured), and the drag factor that then gives the measured apogee.
	 */
	static Map<String, Object> boostReport(Boost b, Simulation base, OpenRocketDocument doc, Log log, Metrics measured,
			Metrics simulated, double apogeeOnlyFit, List<Svg.Series> climbCurves) {
		double apogeeMeasured = measured.apogee(), apogeeSim = simulated.apogee();
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("burnTime", Map.of("measured", Units.fmt(b.burnTime(), Dim.TIME), "simulated", Units.fmt(b.simBurnTime(), Dim.TIME)));
		r.put("burnoutVelocity", row("burnout velocity (integrated accelerometer)", b.vBurnout(), b.simVBurnout(), Dim.VELOCITY));
		r.put("coastDrag", Units.num(b.dragRatio()) + " x OpenRocket's (drag / mass / v^2, " + Units.num(b.coastFrom())
				+ " to " + Units.num(b.coastTo()) + " s after burnout)");
		double dragC = Math.max(0.5, Math.min(2.0, b.dragRatio()));
		double[] thrusts = { 0.8, 0.85, 0.9, 0.95, 1.0, 1.05, 1.1, 1.15, 1.2 };
		List<Simulation> sims = new ArrayList<>();
		for (double k : thrusts) {
			sims.add(Variants.listen(Variants.of(base, doc, null, null), new MonteCarlo.Scaling(dragC, k)));
		}
		List<Variants.Run> runs = Variants.runAll(sims);
		double[] vb = new double[thrusts.length];
		for (int i = 0; i < runs.size(); i++) {
			if (!runs.get(i).ok()) {
				return r;
			}
			Simulation sm = runs.get(i).sim();
			vb[i] = burnoutVelocity(sm);
		}
		double kT = interpolateFactor(thrusts, vb, b.vBurnout());
		if (Double.isNaN(kT)) {
			r.put("thrustFactor", "outside 0.8-1.2 (check the accelerometer's range and axis)");
			return r;
		}
		double[] drags = { 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.35, 1.5, 1.75, 2.0 };
		sims = new ArrayList<>();
		for (double k : drags) {
			sims.add(Variants.listen(Variants.of(base, doc, null, null), new MonteCarlo.Scaling(k, kT)));
		}
		sims.add(Variants.listen(Variants.of(base, doc, null, null), new MonteCarlo.Scaling(1, kT)));
		runs = Variants.runAll(sims);
		double[] apo = new double[drags.length];
		for (int i = 0; i < drags.length; i++) {
			if (!runs.get(i).ok()) {
				return r;
			}
			apo[i] = runs.get(i).sim().getSimulatedData().getMaxAltitude();
		}
		double apoThrustOnly = runs.get(drags.length).sim().getSimulatedData().getMaxAltitude();
		double kD = interpolateFactor(drags, apo, apogeeMeasured);
		r.put("thrustFactor", Units.num(kT) + " x the motor's thrust gives the measured burnout velocity");
		r.put("dragFactor", Double.isNaN(kD) ? "outside 0.5-2.0 with that thrust"
				: Units.num(kD) + " x OpenRocket's drag then gives the measured apogee (the coast measured "
						+ Units.num(b.dragRatio()) + ")");
		if (!Double.isNaN(kD)) {
			List<Simulation> checks = new ArrayList<>();
			checks.add(Variants.listen(Variants.of(base, doc, null, null), new MonteCarlo.Scaling(kD, kT)));
			checks.add(Variants.of(base, doc, null, null));
			if (!Double.isNaN(apogeeOnlyFit)) {
				checks.add(Variants.listen(Variants.of(base, doc, null, null), new MonteCarlo.Scaling(apogeeOnlyFit, 1)));
			}
			List<Variants.Run> cr = Variants.runAll(checks);
			Variants.Run check = cr.get(0);
			if (cr.stream().allMatch(Variants.Run::ok)) {
				// How well each version of the flight follows the measured climb (lift-off to apogee).
				Map<String, Object> climb = new LinkedHashMap<>();
				climb.put("OpenRocket as is", Units.fmt(climbRms(cr.get(1).sim(), log, measured), Dim.DISTANCE));
				if (cr.size() > 2) {
					climb.put("drag x" + Units.num(apogeeOnlyFit) + " (apogee alone)",
							Units.fmt(climbRms(cr.get(2).sim(), log, measured), Dim.DISTANCE));
				}
				climb.put("thrust x" + Units.num(kT) + ", drag x" + Units.num(kD),
						Units.fmt(climbRms(check.sim(), log, measured), Dim.DISTANCE));
				r.put("climbRmsError", climb);
				climbCurves.add(new Svg.Series("OpenRocket as is", simTrack(cr.get(1).sim())[0], simTrack(cr.get(1).sim())[1]));
				if (cr.size() > 2) {
					climbCurves.add(new Svg.Series("drag x" + Units.num(apogeeOnlyFit), simTrack(cr.get(2).sim())[0],
							simTrack(cr.get(2).sim())[1]));
				}
				climbCurves.add(new Svg.Series("thrust x" + Units.num(kT) + ", drag x" + Units.num(kD),
						simTrack(check.sim())[0], simTrack(check.sim())[1]));
			}
			if (check.ok()) {
				double[][] tr = simTrack(check.sim());
				int ia = 0;
				for (int i = 1; i < tr[1].length; i++) {
					ia = tr[1][i] > tr[1][ia] ? i : ia;
				}
				Map<String, Object> c = new LinkedHashMap<>();
				c.put("apogee", Units.fmt(tr[1][ia], Dim.DISTANCE));
				c.put("timeToApogee", Units.fmt(tr[0][ia], Dim.TIME));
				c.put("burnoutVelocity", Units.fmt(burnoutVelocity(check.sim()), Dim.VELOCITY));
				r.put("calibratedFlight", c);
			}
		}
		double total = apogeeMeasured - apogeeSim, fromBoost = apoThrustOnly - apogeeSim;
		r.put("apogeeDifference", Units.fmt(total, Dim.DISTANCE) + " in all: " + Units.fmt(fromBoost, Dim.DISTANCE)
				+ " from the boost (thrust x" + Units.num(kT) + ") and " + Units.fmt(total - fromBoost, Dim.DISTANCE)
				+ " from drag");
		r.put("reading", Math.abs(fromBoost) > Math.abs(total - fromBoost)
				? "Mostly the boost: the motor delivered less (or more) than its thrust curve; calibrate drag only after "
						+ "allowing for it."
				: "Mostly drag: the boost matched the thrust curve, so a drag factor is the right calibration.");
		r.put("caveats", "Assumes a near-vertical boost and an accelerometer whose gain is right (its offset is removed "
				+ "from the pad reading); motors of one type vary by a few percent in impulse.");
		return r;
	}

	/**
	 * Root-mean-square altitude difference between a simulated flight and the log over the climb, the log's time put
	 * on the simulation's clock by its 10 m crossing.
	 */
	static double climbRms(Simulation sim, Log log, Metrics measured) {
		double[][] st = simTrack(sim);
		Metrics s = metrics(st[0], st[1]);
		double shift = measured.launch() - s.launch();
		double sum = 0;
		int n = 0;
		double end = log.t()[0];
		for (int i = 1, ia = 0; i < log.t().length; i++) {
			ia = log.alt()[i] > log.alt()[ia] ? i : ia;
			end = log.t()[ia];
		}
		for (int i = 0; i < log.t().length; i++) {
			double tl = log.t()[i];
			if (tl < measured.launch() || tl > end) {
				continue;
			}
			Double h = interpolate(st[0], st[1], tl - shift);
			if (h != null) {
				sum += (h - log.alt()[i]) * (h - log.alt()[i]);
				n++;
			}
		}
		return n == 0 ? Double.NaN : Math.sqrt(sum / n);
	}

	/** Simulated speed at the first burnout. */
	private static double burnoutVelocity(Simulation sim) {
		Branch br = Branch.of(sim.getSimulatedData().getBranch(0));
		for (FlightEvent e : sim.getSimulatedData().getBranch(0).getEvents()) {
			if (e.getType() == FlightEvent.Type.BURNOUT) {
				Double v = interpolate(br.time, br.col(FlightDataType.TYPE_VELOCITY_TOTAL), e.getTime());
				return v == null ? Double.NaN : v;
			}
		}
		return Double.NaN;
	}

	/** The factor at which a monotonic result crosses {@code target} (linear between the tried factors), else NaN. */
	static double interpolateFactor(double[] factors, double[] results, double target) {
		for (int i = 1; i < factors.length; i++) {
			double a0 = results[i - 1], a1 = results[i];
			if ((target <= a0 && target >= a1) || (target >= a0 && target <= a1)) {
				return a1 == a0 ? factors[i] : factors[i - 1] + (target - a0) / (a1 - a0) * (factors[i] - factors[i - 1]);
			}
		}
		return Double.NaN;
	}

	private static Svg.Series clip(Svg.Series s, double tEnd) {
		int n = 0;
		while (n < s.x().length && s.x()[n] <= tEnd) {
			n++;
		}
		return new Svg.Series(s.name(), java.util.Arrays.copyOf(s.x(), n), java.util.Arrays.copyOf(s.y(), n));
	}

	/** Two curves resampled on one time grid (null where a curve has no data), from the earlier start to the later end. */
	static Map<String, Object> grid(double[] t1, double[] y1, double[] t2, double[] y2, int points) {
		double t0 = Math.max(0, Math.min(t1[0], t2[0])), tEnd = Math.max(t1[t1.length - 1], t2[t2.length - 1]);
		double[] time = new double[points];
		Double[] a = new Double[points], b = new Double[points];
		for (int i = 0; i < points; i++) {
			time[i] = t0 + (tEnd - t0) * i / (points - 1);
			a[i] = interpolate(t1, y1, time[i]);
			b[i] = interpolate(t2, y2, time[i]);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("time", time);
		m.put("simulated", a);
		m.put("measured", b);
		return m;
	}

	static Double interpolate(double[] t, double[] y, double x) {
		if (x < t[0] || x > t[t.length - 1]) {
			return null;
		}
		int lo = 0, hi = t.length - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (t[mid] <= x) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double span = t[hi] - t[lo];
		return span <= 0 ? y[lo] : y[lo] + (y[hi] - y[lo]) * (x - t[lo]) / span;
	}

	private static double[] scale(double[] v, double k) {
		double[] o = new double[v.length];
		for (int i = 0; i < v.length; i++) {
			o[i] = v[i] * k;
		}
		return o;
	}

	private static Map<String, Object> si(String name, double measured, double simulated, Dim d) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("quantity", name);
		r.put("dim", d.name());
		r.put("measured", Double.isNaN(measured) ? null : measured);
		r.put("simulated", Double.isNaN(simulated) ? null : simulated);
		return r;
	}

	private static Map<String, Object> row(String name, double measured, double simulated, Dim d) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("quantity", name);
		r.put("measured", Units.fmt(measured, d));
		r.put("simulated", Units.fmt(simulated, d));
		r.put("difference", Double.isNaN(measured) || Double.isNaN(simulated) || simulated == 0 ? "n/a"
				: "measured " + String.format(Locale.ROOT, "%.2f", Math.abs(100 * (measured - simulated) / simulated)) + "% "
						+ (measured < simulated ? "lower" : "higher"));
		return r;
	}
}
