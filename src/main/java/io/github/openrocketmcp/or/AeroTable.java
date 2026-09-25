package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Imported aerodynamic tables (RASAero II "export aero data" CSV, or any Mach / CD [/ CP] table). The drag replaces
 * OpenRocket's axial drag in every simulation of the design (power-on / power-off as the motor burns) until the first
 * stage separation; the CP gives the stability margin against the simulated CG, as Launch Canada asks for airframes
 * with diameter changes.
 */
public final class AeroTable {
	private AeroTable() {
	}

	/** SI: cp in m from the nose tip (NaN when the table has no CP). Sorted by Mach, one row per Mach (lowest alpha). */
	public record Table(double[] mach, double[] cdOff, double[] cdOn, double[] cp, String source, boolean useDrag) {
		public double cd(double m, boolean powered) {
			return interp(mach, powered ? cdOn : cdOff, m);
		}

		public double cpAt(double m) {
			return interp(mach, cp, m);
		}

		public boolean hasCp() {
			for (double v : cp) {
				if (!Double.isNaN(v)) {
					return true;
				}
			}
			return false;
		}
	}

	private static final Map<String, Table> TABLES = new ConcurrentHashMap<>();
	private static final Set<Simulation> WITHOUT = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

	public static void set(Rocket r, Table t) {
		TABLES.put(r.getID().toString(), t);
	}

	public static void clear(Rocket r) {
		TABLES.remove(r.getID().toString());
	}

	/** The table of a rocket or any copy of it (copies keep the original ids). */
	public static Table of(Rocket r) {
		return TABLES.get(r.getID().toString());
	}

	// ------------------------------------------------------------------------------------ kept with the design

	static final String FORMAT = "openrocket-mcp aero table 1";

	/** The file an imported table is kept in next to a design: rocket.ork -> rocket.aero.json. */
	public static java.nio.file.Path sidecar(java.nio.file.Path ork) {
		String n = ork.getFileName().toString();
		String stem = n.toLowerCase(Locale.ROOT).endsWith(".ork") ? n.substring(0, n.length() - 4) : n;
		return ork.resolveSibling(stem + ".aero.json");
	}

	/** SI JSON (Mach, CD power off / on, CP in m from the nose tip or null), readable and diffable. */
	public static String toJson(Table t) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty("format", FORMAT);
		o.addProperty("source", t.source());
		o.addProperty("useDrag", t.useDrag());
		com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
		for (int i = 0; i < t.mach().length; i++) {
			com.google.gson.JsonObject r = new com.google.gson.JsonObject();
			r.addProperty("mach", t.mach()[i]);
			r.addProperty("cdPowerOff", t.cdOff()[i]);
			r.addProperty("cdPowerOn", t.cdOn()[i]);
			if (Double.isNaN(t.cp()[i])) {
				r.add("cpFromNoseTip_m", com.google.gson.JsonNull.INSTANCE);
			} else {
				r.addProperty("cpFromNoseTip_m", t.cp()[i]);
			}
			rows.add(r);
		}
		o.add("rows", rows);
		return new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(o);
	}

	public static Table fromJson(String json) {
		com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
		if (!o.has("format") || !FORMAT.equals(o.get("format").getAsString())) {
			throw new ToolException("Not an aero table saved by openrocket-mcp.");
		}
		com.google.gson.JsonArray rows = o.getAsJsonArray("rows");
		int n = rows.size();
		double[] mach = new double[n], off = new double[n], on = new double[n], cp = new double[n];
		for (int i = 0; i < n; i++) {
			com.google.gson.JsonObject r = rows.get(i).getAsJsonObject();
			mach[i] = r.get("mach").getAsDouble();
			off[i] = r.get("cdPowerOff").getAsDouble();
			on[i] = r.get("cdPowerOn").getAsDouble();
			cp[i] = r.has("cpFromNoseTip_m") && !r.get("cpFromNoseTip_m").isJsonNull() ? r.get("cpFromNoseTip_m").getAsDouble() : Double.NaN;
		}
		return new Table(mach, off, on, cp, o.get("source").getAsString(), o.get("useDrag").getAsBoolean());
	}

	/** Runs {@code sim} with OpenRocket's own drag even if the design has a table (for comparisons). */
	public static Simulation without(Simulation sim) {
		WITHOUT.add(sim);
		return sim;
	}

	/** Listener to attach for {@code sim}, or null. */
	static AbstractSimulationListener listenerFor(Simulation sim) {
		if (WITHOUT.remove(sim)) {
			return null;
		}
		Table t = of(sim.getRocket());
		return t == null || !t.useDrag() ? null : new DragOverride(t);
	}

	/** Replaces the axial drag coefficient with the table's (power-on while any motor burns). */
	static final class DragOverride extends AbstractSimulationListener {
		final Table t;
		double mach = Double.NaN;
		boolean powered;
		int stages = -1;

		DragOverride(Table t) {
			this.t = t;
		}

		@Override
		public FlightConditions postFlightConditions(SimulationStatus status, FlightConditions c) {
			mach = c.getMach();
			return null;
		}

		@Override
		public double postSimpleThrustCalculation(SimulationStatus status, double thrust) {
			powered = thrust > 1e-6;
			return Double.NaN;
		}

		@Override
		public AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces) {
			int active = status.getConfiguration().getActiveStageCount();
			if (stages < 0) {
				stages = active;
			}
			if (active != stages || Double.isNaN(mach) || forces == null) {
				return null; // the table describes the vehicle as imported; after staging use OpenRocket's model
			}
			double cd = t.cd(mach, powered);
			if (Double.isNaN(cd)) {
				return null;
			}
			double ratio = forces.getCDaxial() > 0 ? cd / forces.getCDaxial() : 1;
			forces.setCDaxial(cd);
			forces.setCD(forces.getCD() * ratio);
			return forces;
		}

		@Override
		public boolean isSystemListener() {
			return true;
		}
	}

	static double interp(double[] x, double[] y, double v) {
		int n = x.length;
		if (n == 0 || Double.isNaN(v)) {
			return Double.NaN;
		}
		if (v <= x[0]) {
			return y[0];
		}
		if (v >= x[n - 1]) {
			return y[n - 1];
		}
		int i = java.util.Arrays.binarySearch(x, v);
		if (i >= 0) {
			return y[i];
		}
		i = -i - 1;
		double f = (v - x[i - 1]) / (x[i] - x[i - 1]);
		return y[i - 1] + f * (y[i] - y[i - 1]);
	}

	// ------------------------------------------------------------------------------------------- parsing

	/**
	 * Parses a CSV (comma, semicolon or tab separated). Recognized headers (case-insensitive): Mach; Alpha (only the
	 * lowest alpha is kept); CD Power-Off / CD Power-On / CD; CP (RASAero writes inches from the nose tip). Without a
	 * header row, columns are Mach, CD[, CP].
	 */
	public static Table parse(String text, String source, double cpUnit, boolean useDrag) {
		List<String[]> rows = new ArrayList<>();
		for (String line : text.split("\\r?\\n")) {
			if (!line.isBlank()) {
				rows.add(line.split("[,;\\t]"));
			}
		}
		if (rows.size() < 2) {
			throw new ToolException("The aero table needs at least two rows.");
		}
		int iMach = 0, iAlpha = -1, iOff = 1, iOn = -1, iCd = -1, iCp = -1;
		int start = 0;
		if (!isNumber(rows.get(0)[0])) {
			String[] h = rows.get(0);
			iMach = iOff = -1;
			for (int i = 0; i < h.length; i++) {
				String k = h[i].trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
				if (k.startsWith("mach")) {
					iMach = i;
				} else if (k.startsWith("alpha") || k.equals("aoa")) {
					iAlpha = i;
				} else if (k.startsWith("cd power-off") || k.startsWith("cd power off") || k.startsWith("cd (power off)")) {
					iOff = i;
				} else if (k.startsWith("cd power-on") || k.startsWith("cd power on") || k.startsWith("cd (power on)")) {
					iOn = i;
				} else if (k.equals("cd") || k.startsWith("cd ")) {
					iCd = iCd < 0 ? i : iCd;
				} else if (k.equals("cp") || k.startsWith("cp ") || k.startsWith("cp(")) {
					iCp = i;
				}
			}
			if (iOff < 0) {
				iOff = iCd;
			}
			if (iMach < 0 || iOff < 0) {
				throw new ToolException("Could not find Mach and CD columns in the header: " + String.join(",", h));
			}
			start = 1;
		} else if (rows.get(0).length >= 3) {
			iCp = 2;
		}
		// Keep the lowest-alpha row for each Mach number.
		TreeMap<Double, double[]> byMach = new TreeMap<>();
		for (int r = start; r < rows.size(); r++) {
			String[] row = rows.get(r);
			try {
				double m = num(row, iMach);
				double alpha = iAlpha >= 0 ? num(row, iAlpha) : 0;
				double off = num(row, iOff);
				double on = iOn >= 0 ? num(row, iOn) : off;
				double cp = iCp >= 0 && iCp < row.length ? num(row, iCp) * cpUnit : Double.NaN;
				double[] cur = byMach.get(m);
				if (Double.isNaN(m) || Double.isNaN(off)) {
					continue;
				}
				if (cur == null || alpha < cur[0]) {
					byMach.put(m, new double[] { alpha, off, on, cp });
				}
			} catch (RuntimeException e) {
				throw new ToolException("Bad number in aero table row " + (r + 1) + ": " + String.join(",", row));
			}
		}
		if (byMach.size() < 2) {
			throw new ToolException("The aero table needs at least two Mach numbers.");
		}
		int n = byMach.size(), i = 0;
		double[] mach = new double[n], off = new double[n], on = new double[n], cp = new double[n];
		for (Map.Entry<Double, double[]> e : byMach.entrySet()) {
			mach[i] = e.getKey();
			off[i] = e.getValue()[1];
			on[i] = e.getValue()[2];
			cp[i] = e.getValue()[3];
			i++;
		}
		for (double v : off) {
			if (!(v > 0 && v < 5)) {
				throw new ToolException("CD value " + v + " is out of range (0-5); check the column mapping.");
			}
		}
		return new Table(mach, off, on, cp, source, useDrag);
	}

	private static boolean isNumber(String s) {
		try {
			Double.parseDouble(s.trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static double num(String[] row, int i) {
		return i < row.length && !row[i].isBlank() ? Double.parseDouble(row[i].trim()) : Double.NaN;
	}

	// ------------------------------------------------------------------------------------------- stability

	/** Minimum margin (cal) with the table's CP and the simulated CG, rail exit to apogee or first separation. */
	public record Margin(double min, double time, double mach) {
	}

	public static Margin minMargin(Simulation sim, Table t, double refDiameter) {
		if (!t.hasCp()) {
			return null;
		}
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double rail = Sims.eventTime(b, FlightEvent.Type.LAUNCHROD);
		double end = Sims.eventTime(b, FlightEvent.Type.APOGEE);
		double sep = Sims.eventTime(b, FlightEvent.Type.STAGE_SEPARATION);
		if (!Double.isNaN(sep)) {
			end = Math.min(end, sep);
		}
		if (Double.isNaN(rail) || Double.isNaN(end)) {
			return null;
		}
		double[] cg = br.col(FlightDataType.TYPE_CG_LOCATION), mach = br.col(FlightDataType.TYPE_MACH_NUMBER);
		double best = Double.POSITIVE_INFINITY, tb = Double.NaN, mb = Double.NaN;
		for (int i = br.index(rail); i < br.size() && br.time[i] <= end; i++) {
			if (br.airspeed(i) < 30.48 || Double.isNaN(cg[i])) {
				continue;
			}
			double m = (t.cpAt(mach[i]) - cg[i]) / refDiameter;
			if (m < best) {
				best = m;
				tb = br.time[i];
				mb = mach[i];
			}
		}
		return Double.isInfinite(best) ? null : new Margin(best, tb, mb);
	}

	public static String describe(Table t) {
		return t.mach().length + " Mach points " + Units.num(t.mach()[0]) + "-" + Units.num(t.mach()[t.mach().length - 1])
				+ (t.hasCp() ? ", with CP" : "") + (t.useDrag() ? ", drag applied to simulations" : ", drag not applied")
				+ " (" + t.source() + ")";
	}

	static String fmtCp(double v) {
		return Units.fmt(v, Dim.LENGTH);
	}
}
