package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Flight logs (altimeter CSV: time, altitude) against the simulation: apogee, time to apogee and descent rates, and a
 * drag multiplier that makes OpenRocket reproduce the measured apogee (calibration for the next flight).
 */
public final class FlightLog {
	private FlightLog() {
	}

	/** Time (s from the first sample) and altitude above the pad (m). */
	public record Log(double[] t, double[] alt, String note) {
	}

	/**
	 * Parses an altimeter CSV. Columns are found by header ("time", "alt"); without a header the first two columns
	 * are time and altitude. Units come from the header ("(ft)", "(m)", "(ms)") or {@code altUnit} ("ft", "m", or
	 * null = auto, feet when unknown). The pad altitude (median of the first samples) is subtracted.
	 */
	public static Log parse(String text, String altUnit) {
		String[] lines = text.split("\\r?\\n");
		int iT = 0, iA = 1;
		double tScale = 1, aScale = Double.NaN;
		List<double[]> rows = new ArrayList<>();
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
					if (k.contains("time") || k.trim().equals("t") || k.contains("sec")) {
						iT = i;
						if (k.contains("ms") || k.contains("milli")) {
							tScale = 0.001;
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
		if (t[n - 1] > 3000) { // milliseconds without a header
			for (int i = 0; i < n; i++) {
				t[i] /= 1000;
			}
		}
		return new Log(t, a, note);
	}

	private static boolean numeric(String s) {
		try {
			Double.parseDouble(s.trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** Key numbers of a trajectory; times are from launch (first crossing of 10 m minus the time to climb it). */
	record Metrics(double launch, double apogee, double timeToApogee, double upperDescent, double lowerDescent) {
	}

	static Metrics metrics(double[] t, double[] alt) {
		int ia = 0;
		for (int i = 1; i < alt.length; i++) {
			if (alt[i] > alt[ia]) {
				ia = i;
			}
		}
		double launch = Double.NaN;
		for (int i = 0; i <= ia; i++) {
			if (alt[i] > 10) {
				launch = i == 0 ? t[0] : t[i - 1];
				break;
			}
		}
		double apo = alt[ia];
		return new Metrics(launch, apo, t[ia] - launch, rate(t, alt, ia, 0.9 * apo, 0.6 * apo),
				rate(t, alt, ia, Math.min(150, 0.3 * apo), 20));
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
			String title) throws java.io.IOException {
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
		Metrics m = metrics(log.t(), log.alt());
		double[][] st = simTrack(nominal);
		Metrics s = metrics(st[0], st[1]);

		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> rows = new ArrayList<>();
		rows.add(row("apogee", m.apogee(), s.apogee(), Dim.DISTANCE));
		rows.add(row("timeToApogee", m.timeToApogee(), s.timeToApogee(), Dim.TIME));
		rows.add(row("descentRate 90%-60% of apogee (drogue)", m.upperDescent(), s.upperDescent(), Dim.VELOCITY));
		rows.add(row("descentRate last 150 m (main)", m.lowerDescent(), s.lowerDescent(), Dim.VELOCITY));
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
		List<String> notes = new ArrayList<>();
		if (!log.note().isEmpty()) {
			notes.add(log.note());
		}
		notes.add("Match the simulation to the day: wind, launch angle, site altitude and temperature (run with overrides), "
				+ "and the as-flown mass (edit or override masses) before reading the drag factor.");
		notes.add("Barometric altimeters read low near Mach 1 and lag at apogee; the drogue and main bands are fitted slopes.");
		out.put("notes", notes);

		if (svgPath != null) {
			java.nio.file.Path p = java.nio.file.Path.of(svgPath).toAbsolutePath();
			if (p.getParent() != null) {
				java.nio.file.Files.createDirectories(p.getParent());
			}
			double shift = m.launch() - s.launch(); // align launches
			double[] lt = new double[log.t().length];
			for (int i = 0; i < lt.length; i++) {
				lt[i] = log.t()[i] - shift;
			}
			boolean imperial = Units.system() == io.github.openrocketmcp.units.UnitSystem.IMPERIAL;
			double k = imperial ? 1 / 0.3048 : 1;
			java.nio.file.Files.writeString(p, io.github.openrocketmcp.report.Svg.lines(title, "Time (s)",
					"Altitude (" + (imperial ? "ft" : "m") + ")",
					List.of(new io.github.openrocketmcp.report.Svg.Series("simulated", st[0], scale(st[1], k)),
							new io.github.openrocketmcp.report.Svg.Series("measured", lt, scale(log.alt(), k))),
					Double.NaN, null, List.of()));
			out.put("plot", p.toString());
		}
		return out;
	}

	private static double[] scale(double[] v, double k) {
		double[] o = new double[v.length];
		for (int i = 0; i < v.length; i++) {
			o[i] = v[i] * k;
		}
		return o;
	}

	private static Map<String, Object> row(String name, double measured, double simulated, Dim d) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("quantity", name);
		r.put("measured", Units.fmt(measured, d));
		r.put("simulated", Units.fmt(simulated, d));
		r.put("difference", Double.isNaN(measured) || Double.isNaN(simulated) || simulated == 0 ? "n/a"
				: Units.num(100 * (measured - simulated) / simulated) + "%");
		return r;
	}
}
