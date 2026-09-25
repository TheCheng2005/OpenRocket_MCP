package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.ExternalComponent;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Aerodynamic heating screen along the simulated flight.
 *
 * <pre>
 *   stagnation temperature  T0 = T (1 + (g-1)/2 M^2)             nose tip, fin leading edges
 *   recovery temperature    Tr = T (1 + r (g-1)/2 M^2), r = 0.89  body surfaces (turbulent)
 *   Sutton-Graves           q  = 1.7415e-4 sqrt(rho / Rn) V^3     cold-wall stagnation heat flux, W/m2
 * </pre>
 * These are adiabatic-wall / cold-wall bounds: the real surface lags them (thermal mass, short exposure), so a surface
 * that stays below its material limit is clear, and one that exceeds it needs a thermal analysis or a better material.
 */
public final class Heating {
	private Heating() {
	}

	static final double GAMMA = 1.4, RECOVERY = 0.89, SUTTON_GRAVES = 1.7415e-4;

	public static double stagnation(double t, double mach) {
		return t * (1 + (GAMMA - 1) / 2 * mach * mach);
	}

	public static double recovery(double t, double mach) {
		return t * (1 + RECOVERY * (GAMMA - 1) / 2 * mach * mach);
	}

	/** Cold-wall stagnation-point heat flux (W/m2) for nose radius rn (m). */
	public static double suttonGraves(double rho, double v, double rn) {
		return SUTTON_GRAVES * Math.sqrt(rho / rn) * v * v * v;
	}

	/** Surface kinds and their temperature model. */
	enum Kind {
		NOSE_TIP, LEADING_EDGE, BODY
	}

	public static Map<String, Object> analyze(Simulation sim, Standards std, double tipRadius) {
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double[] t = br.time, temp = br.col(FlightDataType.TYPE_AIR_TEMPERATURE), mach = br.col(FlightDataType.TYPE_MACH_NUMBER),
				rho = br.col(FlightDataType.TYPE_AIR_DENSITY);
		// Flight-wide extremes.
		double t0Max = 0, trMax = 0, qMax = 0, load = 0, tAtMax = Double.NaN, machMax = 0;
		for (int i = 0; i < t.length; i++) {
			if (Double.isNaN(temp[i]) || Double.isNaN(mach[i])) {
				continue;
			}
			double t0 = stagnation(temp[i], mach[i]);
			if (t0 > t0Max) {
				t0Max = t0;
				tAtMax = t[i];
				machMax = mach[i];
			}
			trMax = Math.max(trMax, recovery(temp[i], mach[i]));
			double q = suttonGraves(rho[i], br.airspeed(i), tipRadius);
			if (!Double.isNaN(q)) {
				qMax = Math.max(qMax, q);
				if (i > 0) {
					load += q * (t[i] - t[i - 1]);
				}
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> flight = new LinkedHashMap<>();
		flight.put("maxMach", Units.num(machMax));
		flight.put("maxStagnationTemperature", Units.fmt(t0Max, Dim.TEMPERATURE) + " at t=" + Units.num(tAtMax) + " s");
		flight.put("maxRecoveryTemperature", Units.fmt(trMax, Dim.TEMPERATURE) + " (body surfaces, turbulent)");
		flight.put("noseTipHeatFlux", Units.num(qMax / 1e4) + " W/cm2 peak, " + Units.num(load / 1e4) + " J/cm2 total (Sutton-Graves, "
				+ "tip radius " + Units.fmt(tipRadius, Dim.LENGTH) + ", cold wall)");
		out.put("flight", flight);

		List<Map<String, Object>> surfaces = new ArrayList<>();
		for (RocketComponent c : fc.getActiveComponents()) {
			Kind k = c instanceof NoseCone ? Kind.NOSE_TIP : c instanceof FinSet ? Kind.LEADING_EDGE
					: c instanceof BodyTube || c instanceof Transition ? Kind.BODY : null;
			if (k == null || !(c instanceof ExternalComponent ext)) {
				continue;
			}
			String mat = ext.getMaterial().getName();
			double worst = 0, above = 0;
			Object[] lim = std.materialValue("structures.maxServiceTemperature", mat, Dim.TEMPERATURE);
			double limit = lim == null ? Double.NaN : (Double) lim[0];
			for (int i = 0; i < t.length; i++) {
				if (Double.isNaN(temp[i]) || Double.isNaN(mach[i])) {
					continue;
				}
				double tt = k == Kind.BODY ? recovery(temp[i], mach[i]) : stagnation(temp[i], mach[i]);
				worst = Math.max(worst, tt);
				if (!Double.isNaN(limit) && tt > limit && i > 0) {
					above += t[i] - t[i - 1];
				}
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("surface", c.getName() + (k == Kind.NOSE_TIP ? " (tip)" : k == Kind.LEADING_EDGE ? " (leading edges)" : ""));
			m.put("material", mat);
			m.put("maxTemperature", Units.fmt(worst, Dim.TEMPERATURE) + (k == Kind.BODY ? " (recovery)" : " (stagnation)"));
			if (Double.isNaN(limit)) {
				m.put("limit", "unknown for this material: add it to structures.maxServiceTemperature");
				m.put("status", "INFO");
			} else {
				m.put("limit", Units.fmt(limit, Dim.TEMPERATURE) + " (" + lim[1] + ")");
				m.put("status", worst <= limit ? "OK: below the material limit for the whole flight"
						: "CHECK: above the limit for " + Units.num(above) + " s (adiabatic bound; the surface lags - do a thermal "
								+ "analysis, or use a higher-temperature resin, a metal tip or leading-edge protection)");
			}
			surfaces.add(m);
		}
		out.put("surfaces", surfaces);
		out.put("notes", List.of(
				"Adiabatic-wall temperatures are upper bounds: real skins heat up with a lag (thermal mass) and flights are short. "
						+ "Below the limit = clear; above it = check with a transient thermal estimate or test.",
				"Heating matters from about Mach 2; below Mach 1.5 the rise is a few tens of degrees. Direct sun on the pad can "
						+ "also soften low-Tg resins and 3D-printed parts before launch.",
				"Material limits are typical glass-transition / softening temperatures from structures.maxServiceTemperature; "
						+ "use your resin's datasheet (post-curing raises epoxy Tg a lot)."));
		return out;
	}
}
