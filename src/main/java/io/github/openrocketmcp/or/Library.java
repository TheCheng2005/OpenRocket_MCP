package io.github.openrocketmcp.or;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * The team's design history: every OpenRocket (.ork, any version) and RockSim (.rkt) file under a folder, summarised
 * (size, mass, motors, predicted apogee, materials, recovery), with the altimeter logs (.csv) found beside them
 * matched to their design, so past rockets can be searched and predicted apogees compared with what flew.
 *
 * <p>Files are read without being opened as designs. Summaries are cached by path, size and modification time, so
 * asking again about the same folder is quick.
 */
public final class Library {
	private Library() {
	}

	static final int MAX_FILES = 2000;
	private static final Pattern YEAR = Pattern.compile("(?<![0-9])(19[89][0-9]|20[0-9][0-9])(?![0-9])");

	/** One past design. Lengths in m, mass in kg, apogee in m (NaN when unknown). */
	public record Entry(Path file, String relPath, String name, int year, String yearSource, int stages, double length,
			double diameter, double launchMass, double totalImpulse, String motorClass, List<String> motors,
			double predictedApogee, String predictionSource, double stability, String airframeMaterial, String finMaterial,
			List<String> recovery, String error) {
		boolean ok() {
			return error == null;
		}
	}

	/** One altimeter log and what it recorded. */
	public record Flight(Path file, String relPath, int year, double apogee, Entry design, String match) {
	}

	/** A scanned folder. */
	public record Scan(Path root, List<Entry> designs, List<Flight> flights, List<String> skipped) {
	}

	private record Cached(long size, long modified, Entry entry) {
	}

	private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

	/** Scans the folder (recursively) for designs and flight logs. */
	public static Scan scan(Path root, boolean simulate) throws IOException {
		if (!Files.isDirectory(root)) {
			throw new ToolException("Not a folder: " + root + ". Point the library at the folder that holds the team's past "
					+ ".ork / .rkt files (sub-folders are searched too).");
		}
		OrRuntime.init();
		List<Path> designFiles = new ArrayList<>(), logFiles = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root, 8)) {
			walk.filter(Files::isRegularFile).sorted().forEach(p -> {
				String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
				if (n.startsWith(".")) {
					return;
				}
				if (n.endsWith(".ork") || n.endsWith(".rkt")) {
					designFiles.add(p);
				} else if (n.endsWith(".csv")) {
					logFiles.add(p);
				}
			});
		}
		if (designFiles.size() > MAX_FILES) {
			throw new ToolException("More than " + MAX_FILES + " design files under " + root + "; point at a smaller folder.");
		}
		CallContext call = CallContext.current();
		call.expect(designFiles.size(), "designs read");
		List<Entry> designs = new ArrayList<>();
		for (Path p : designFiles) {
			call.checkCancelled();
			designs.add(entry(root, p, simulate));
			call.advance();
		}
		List<Flight> flights = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (Path p : logFiles) {
			call.checkCancelled();
			String rel = rel(root, p);
			double apogee;
			try {
				FlightLog.Log log = FlightLog.parse(Files.readString(p), null);
				apogee = FlightLog.metrics(log.t(), log.alt()).apogee();
			} catch (RuntimeException | IOException e) {
				skipped.add(rel + " (not an altimeter log of time and altitude)");
				continue;
			}
			if (!(apogee > 10)) {
				skipped.add(rel + " (no climb above 10 m: not a flight)");
				continue;
			}
			Object[] m = match(p, designs);
			flights.add(new Flight(p, rel, year(rel, p)[0], apogee, (Entry) m[0], (String) m[1]));
		}
		for (Entry e : designs) {
			if (!e.ok()) {
				skipped.add(e.relPath() + " (" + e.error() + ")");
			}
		}
		return new Scan(root, designs, flights, skipped);
	}

	static String rel(Path root, Path p) {
		return root.relativize(p).toString().replace('\\', '/');
	}

	/** {year, 1 when taken from the path (folder or file name) / 0 from the file date}. */
	static int[] year(String rel, Path p) {
		Matcher m = YEAR.matcher(rel);
		int y = -1;
		while (m.find()) {
			y = Integer.parseInt(m.group(1)); // the last one: the file name beats the folder
		}
		if (y > 0) {
			return new int[] { y, 1 };
		}
		try {
			return new int[] { Files.getLastModifiedTime(p).toInstant().atZone(ZoneOffset.UTC).getYear(), 0 };
		} catch (IOException e) {
			return new int[] { 0, 0 };
		}
	}

	static Entry entry(Path root, Path p, boolean simulate) {
		String rel = rel(root, p);
		try {
			BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class);
			Cached c = CACHE.get(p);
			if (c != null && c.size() == at.size() && c.modified() == at.lastModifiedTime().toMillis()
					&& c.entry().relPath().equals(rel) && (!simulate || !Double.isNaN(c.entry().predictedApogee())
							|| c.entry().motors().isEmpty() || c.entry().predictionSource().startsWith("simulation failed"))) {
				return c.entry();
			}
			Entry e = read(p, rel, simulate);
			CACHE.put(p, new Cached(at.size(), at.lastModifiedTime().toMillis(), e));
			return e;
		} catch (IOException e) {
			return failed(p, rel, "unreadable: " + e.getMessage());
		}
	}

	private static Entry failed(Path p, String rel, String why) {
		int[] y = year(rel, p);
		return new Entry(p, rel, p.getFileName().toString(), y[0], y[1] == 1 ? "path" : "file date", 0, Double.NaN,
				Double.NaN, Double.NaN, Double.NaN, "", List.of(), Double.NaN, "", Double.NaN, "", "", List.of(), why);
	}

	private static Entry read(Path p, String rel, boolean simulate) {
		OpenRocketDocument doc;
		try {
			doc = new GeneralRocketLoader(p.toFile()).load();
		} catch (Exception | LinkageError e) {
			return failed(p, rel, "OpenRocket could not read it: " + e.getMessage());
		}
		try {
			FlightConfiguration fc = doc.getRocket().getSelectedConfiguration();
			Analysis.settle(fc);
			double impulse = 0;
			List<String> motors = new ArrayList<>();
			for (MotorConfiguration mc : fc.getActiveMotors()) {
				Motor m = mc.getMotor();
				if (m == null) {
					continue;
				}
				int n = Math.max(1, mc.getMotorCount());
				impulse += m.getTotalImpulseEstimate() * n;
				motors.add((n > 1 ? n + " x " : "") + m.getDesignation());
			}
			String airframe = "", fin = "";
			Set<String> recovery = new LinkedHashSet<>();
			for (RocketComponent c : fc.getRocket()) {
				if (!fc.isComponentActive(c)) {
					continue;
				}
				if (c instanceof BodyTube bt && airframe.isEmpty() && !bt.isMotorMount()) {
					airframe = bt.getMaterial().getName();
				} else if (c instanceof FinSet f && fin.isEmpty()) {
					fin = f.getMaterial().getName();
				} else if (c instanceof RecoveryDevice rd) {
					recovery.add(rd instanceof Parachute pc
							? pc.getName() + " (" + Units.fmt(pc.getDiameter(), Dim.LENGTH) + " parachute)" : rd.getName());
				}
			}
			if (airframe.isEmpty()) {
				for (RocketComponent c : fc.getRocket()) {
					if (c instanceof BodyTube bt && fc.isComponentActive(c)) {
						airframe = bt.getMaterial().getName();
						break;
					}
				}
			}
			double apogee = Double.NaN;
			String source = "not simulated";
			Simulation pick = null;
			for (Simulation s : doc.getSimulations()) {
				if (pick == null || (s.getFlightConfigurationId().equals(fc.getFlightConfigurationID())
						&& !pick.getFlightConfigurationId().equals(fc.getFlightConfigurationID()))) {
					pick = s;
				}
			}
			for (Simulation s : doc.getSimulations()) { // prefer stored results of the rocket's own configuration
				if (s.getSimulatedData() != null && s.getSimulatedData().getBranchCount() > 0
						&& s.getFlightConfigurationId().equals(fc.getFlightConfigurationID())) {
					apogee = s.getSimulatedData().getMaxAltitude();
					source = "saved result of '" + s.getName() + "'";
					break;
				}
			}
			if (Double.isNaN(apogee) && simulate && !motors.isEmpty()) {
				// The file's own simulation when it has one, else OpenRocket's default launch conditions.
				Simulation run = pick != null ? pick.copy() : new Simulation(doc, doc.getRocket());
				if (pick == null) {
					run.setFlightConfigurationId(fc.getFlightConfigurationID());
				}
				try {
					Sims.run(run);
					apogee = run.getSimulatedData().getMaxAltitude();
					source = pick != null ? "simulated now ('" + pick.getName() + "')"
							: "simulated now in OpenRocket's default conditions (no simulation in the file)";
				} catch (RuntimeException e) {
					source = "simulation failed: " + e.getMessage();
				}
			} else if (Double.isNaN(apogee)) {
				source = motors.isEmpty() ? "no motor set" : pick == null ? "no simulation in the file" : "not simulated";
			}
			double stability = Double.NaN;
			try {
				stability = Analysis.stability(fc, 0.3).marginCalibers();
			} catch (RuntimeException e) {
				// no airframe
			}
			int[] y = year(rel, p);
			return new Entry(p, rel, doc.getRocket().getName(), y[0], y[1] == 1 ? "path" : "file date",
					fc.getActiveStageCount(), fc.getLength(), Analysis.maxDiameter(fc),
					MassCalculator.calculateLaunch(fc).getMass(), impulse, impulse > 0 ? Motors.impulseClass(impulse) : "",
					motors, apogee, source, stability, airframe, fin, new ArrayList<>(recovery), null);
		} catch (RuntimeException e) {
			return failed(p, rel, "could not summarise it: " + e.getMessage());
		}
	}

	/**
	 * The design a log belongs to: one whose file or rocket name appears in the log's file name (longest wins), else
	 * the only design in the same folder, else the only design in the parent folder. {design or null, how}.
	 */
	static Object[] match(Path log, List<Entry> designs) {
		String stem = norm(stem(log));
		Entry best = null;
		int bestScore = 0;
		for (Entry e : designs) {
			if (!e.ok()) {
				continue;
			}
			// The file name beats the rocket's name (several files can share one), then the nearest folder wins.
			int score = 0;
			String file = norm(stem(e.file())), name = norm(e.name());
			if (file.length() >= 3 && stem.contains(file)) {
				score = 1000 + file.length();
			} else if (name.length() >= 3 && stem.contains(name)) {
				score = 500 + name.length();
			}
			if (score > 0) {
				score += e.file().getParent().equals(log.getParent()) ? 200 : log.startsWith(e.file().getParent()) ? 100 : 0;
			}
			if (score > bestScore) {
				best = e;
				bestScore = score;
			}
		}
		if (best != null) {
			return new Object[] { best, "name" };
		}
		for (Path dir = log.getParent(); dir != null; dir = dir.getParent()) {
			List<Entry> here = new ArrayList<>();
			for (Entry e : designs) {
				if (e.ok() && e.file().getParent().equals(dir)) {
					here.add(e);
				}
			}
			if (here.size() == 1) {
				return new Object[] { here.get(0), dir.equals(log.getParent()) ? "same folder" : "parent folder" };
			}
			if (!here.isEmpty() || dir.equals(log.getParent().getParent())) {
				break;
			}
		}
		return new Object[] { null, "no match: name the log after its design (e.g. <design>-flight1.csv) or put it in the "
				+ "design's folder" };
	}

	private static String stem(Path p) {
		String n = p.getFileName().toString();
		int dot = n.lastIndexOf('.');
		return dot > 0 ? n.substring(0, dot) : n;
	}

	static String norm(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	/** Search criteria; NaN / null / 0 = any. */
	public record Query(String text, double minDiameter, double maxDiameter, String motorClass, int yearFrom, int yearTo,
			int stages, boolean withFlights, String sort) {
	}

	public static List<Entry> filter(Scan scan, Query q) {
		String[] classes = classRange(q.motorClass());
		List<Entry> out = new ArrayList<>();
		for (Entry e : scan.designs()) {
			if (!e.ok()) {
				continue;
			}
			if (q.text() != null && !q.text().isBlank()) {
				String hay = (e.name() + " " + e.relPath() + " " + String.join(" ", e.motors()) + " " + e.airframeMaterial()
						+ " " + e.finMaterial() + " " + String.join(" ", e.recovery())).toLowerCase(Locale.ROOT);
				boolean all = true;
				for (String w : q.text().toLowerCase(Locale.ROOT).split("\\s+")) {
					all &= w.isEmpty() || hay.contains(w);
				}
				if (!all) {
					continue;
				}
			}
			// 2% slack so "4 in" finds 98 mm and 4.0 in tubes whatever the rounding
			if (!Double.isNaN(q.minDiameter()) && !(e.diameter() >= q.minDiameter() * 0.98)) {
				continue;
			}
			if (!Double.isNaN(q.maxDiameter()) && !(e.diameter() <= q.maxDiameter() * 1.02)) {
				continue;
			}
			if (classes != null && (e.motorClass().length() != 1 || e.motorClass().compareTo(classes[0]) < 0
					|| e.motorClass().compareTo(classes[1]) > 0)) {
				continue;
			}
			if (q.yearFrom() > 0 && e.year() < q.yearFrom() || q.yearTo() > 0 && e.year() > q.yearTo()) {
				continue;
			}
			if (q.stages() > 0 && e.stages() != q.stages()) {
				continue;
			}
			if (q.withFlights() && flightsOf(scan, e).isEmpty()) {
				continue;
			}
			out.add(e);
		}
		Comparator<Entry> by = switch (q.sort() == null ? "year" : q.sort()) {
			case "name" -> Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT));
			case "apogee" -> Comparator.comparingDouble((Entry e) -> Double.isNaN(e.predictedApogee()) ? -1 : e.predictedApogee()).reversed();
			case "mass" -> Comparator.comparingDouble(Entry::launchMass).reversed();
			case "diameter" -> Comparator.comparingDouble(Entry::diameter).reversed();
			case "impulse" -> Comparator.comparingDouble(Entry::totalImpulse).reversed();
			default -> Comparator.comparingInt(Entry::year).reversed().thenComparing(Entry::relPath);
		};
		out.sort(by);
		return out;
	}

	/** "M" -> {M, M}; "L-N" -> {L, N}; null = any. */
	static String[] classRange(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String t = s.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("TO", "-");
		String[] parts = t.split("-");
		String lo = parts[0], hi = parts.length > 1 ? parts[1] : parts[0];
		if (lo.length() != 1 || hi.length() != 1 || !Character.isLetter(lo.charAt(0)) || !Character.isLetter(hi.charAt(0))) {
			throw new ToolException("motorClass is an impulse class letter or range, e.g. \"M\" or \"L-N\".");
		}
		return lo.compareTo(hi) <= 0 ? new String[] { lo, hi } : new String[] { hi, lo };
	}

	public static List<Flight> flightsOf(Scan scan, Entry e) {
		List<Flight> out = new ArrayList<>();
		for (Flight f : scan.flights()) {
			if (f.design() == e) {
				out.add(f);
			}
		}
		return out;
	}

	public static Map<String, Object> render(Scan scan, Entry e) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", e.name());
		m.put("file", e.relPath());
		m.put("year", e.year() + (e.yearSource().equals("path") ? "" : " (file date)"));
		m.put("diameter", Units.fmt(e.diameter(), Dim.LENGTH));
		m.put("length", Units.fmt(e.length(), Dim.LENGTH));
		m.put("launchMass", Units.fmt(e.launchMass(), Dim.MASS));
		if (e.stages() > 1) {
			m.put("stages", e.stages());
		}
		m.put("motors", e.motors().isEmpty() ? "none set" : String.join(", ", e.motors())
				+ (e.motorClass().isEmpty() ? "" : " (" + e.motorClass() + ", " + Units.fmt(e.totalImpulse(), Dim.IMPULSE) + ")"));
		m.put("predictedApogee", Double.isNaN(e.predictedApogee()) ? e.predictionSource()
				: Units.fmt(e.predictedApogee(), Dim.DISTANCE) + " (" + e.predictionSource() + ")");
		m.put("stability", Analysis.cal(e.stability()));
		m.put("airframe", e.airframeMaterial());
		if (!e.finMaterial().isEmpty()) {
			m.put("fins", e.finMaterial());
		}
		if (!e.recovery().isEmpty()) {
			m.put("recovery", e.recovery());
		}
		List<String> fl = new ArrayList<>();
		for (Flight f : flightsOf(scan, e)) {
			fl.add(f.relPath() + ": " + Units.fmt(f.apogee(), Dim.DISTANCE) + " apogee");
		}
		if (!fl.isEmpty()) {
			m.put("flights", fl);
		}
		return m;
	}

	/** Predicted vs measured apogee for every matched log, by year, and the overall bias. */
	public static Map<String, Object> trend(Scan scan, List<Entry> within) {
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> rows = new ArrayList<>();
		TreeMap<Integer, List<Double>> byYear = new TreeMap<>();
		List<Double> all = new ArrayList<>();
		List<String> unmatched = new ArrayList<>();
		for (Flight f : scan.flights()) {
			if (f.design() == null) {
				unmatched.add(f.relPath());
				continue;
			}
			if (!within.contains(f.design()) || Double.isNaN(f.design().predictedApogee())) {
				continue;
			}
			double pred = f.design().predictedApogee();
			double err = (pred - f.apogee()) / f.apogee() * 100;
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("year", f.year());
			r.put("design", f.design().name());
			r.put("log", f.relPath());
			r.put("predicted", Units.fmt(pred, Dim.DISTANCE));
			r.put("measured", Units.fmt(f.apogee(), Dim.DISTANCE));
			r.put("predictionError", (err >= 0 ? "+" : "") + Units.num(err) + "%");
			rows.add(r);
			byYear.computeIfAbsent(f.year(), k -> new ArrayList<>()).add(err);
			all.add(err);
		}
		rows.sort(Comparator.comparingInt(r -> (Integer) r.get("year")));
		out.put("flights", rows);
		if (!all.isEmpty()) {
			List<String> years = new ArrayList<>();
			for (Map.Entry<Integer, List<Double>> y : byYear.entrySet()) {
				years.add(y.getKey() + ": " + signed(mean(y.getValue())) + " mean over " + y.getValue().size() + " flight"
						+ (y.getValue().size() == 1 ? "" : "s"));
			}
			out.put("byYear", years);
			double bias = mean(all);
			double spread = 0;
			for (double e : all) {
				spread += (e - bias) * (e - bias);
			}
			spread = all.size() > 1 ? Math.sqrt(spread / (all.size() - 1)) : Double.NaN;
			out.put("overall", "predictions " + (bias >= 0 ? "over" : "under") + "-estimated apogee by " + Units.num(Math.abs(bias))
					+ "% on average over " + all.size() + " flight" + (all.size() == 1 ? "" : "s")
					+ (Double.isNaN(spread) ? "" : " (scatter " + Units.num(spread) + "%)"));
			if (all.size() >= 2 && Math.abs(bias) > 5) {
				out.put("use", "Scale this year's predicted apogee by " + Units.num(100 / (100 + bias)) + " for a first "
						+ "estimate, and calibrate the drag with compare_flight on the closest past rocket's log.");
			}
		} else {
			out.put("note", "No flight logs matched to designs with a predicted apogee. Put each altimeter CSV (time, "
					+ "altitude) beside its design or name it after the design, e.g. \"maple-2025-flight1.csv\".");
		}
		if (!unmatched.isEmpty()) {
			out.put("unmatchedLogs", unmatched);
		}
		out.put("caveat", "The prediction is the design file's saved (or re-run) simulation, flown in the conditions set in "
				+ "that file, not the day's weather; compare_flight re-flies one log in that day's conditions.");
		return out;
	}

	private static double mean(List<Double> v) {
		double s = 0;
		for (double x : v) {
			s += x;
		}
		return s / v.size();
	}

	private static String signed(double v) {
		return (v >= 0 ? "+" : "") + Units.num(v) + "%";
	}

	/** Past designs ranked by closeness to a rocket: diameter, launch mass and total impulse (log ratios). */
	public static List<Entry> similar(Scan scan, double diameter, double mass, double impulse, Path exclude) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : scan.designs()) {
			if (e.ok() && !e.file().equals(exclude) && e.diameter() > 0 && e.launchMass() > 0) {
				out.add(e);
			}
		}
		out.sort(Comparator.comparingDouble(e -> distance(e, diameter, mass, impulse)));
		return out;
	}

	static double distance(Entry e, double diameter, double mass, double impulse) {
		double d = Math.log(e.diameter() / diameter), m = Math.log(e.launchMass() / mass);
		double i = impulse > 0 && e.totalImpulse() > 0 ? Math.log(e.totalImpulse() / impulse) / 2 : impulse > 0 ? 3 : 0;
		return 2 * d * d + m * m + i * i;
	}
}
