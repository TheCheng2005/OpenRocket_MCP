package io.github.openrocketmcp.or;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration.DeployEvent;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RingComponent;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Altimeter settings from the simulation: what each deployment channel of the primary and backup altimeters is set to,
 * the barometric (Mach) lockout, what the backup's offsets cost in airspeed, and the static ports. Settings are AGL,
 * as altimeters zero on the pad.
 */
public final class AltimeterSettings {
	private AltimeterSettings() {
	}

	/** Team choices (electronics.altimeter in the standards). */
	public record Options(double backupDelay, double backupMainOffset, double baroMach, double lockoutMargin) {
	}

	public static Map<String, Object> sheet(Simulation sim, Options o, double bayVolume, String bayName, Path card)
			throws IOException {
		FlightConfigurationId fcid = sim.getFlightConfigurationId();
		FlightDataBranch main = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(main);
		double apogee = Sims.eventTime(main, FlightEvent.Type.APOGEE);
		if (Double.isNaN(apogee)) {
			throw new ToolException("The simulation never reaches apogee; fix the flight first (run_simulation).");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> flight = new LinkedHashMap<>();
		flight.put("apogee", Units.fmt(br.at(FlightDataType.TYPE_ALTITUDE, apogee), Dim.DISTANCE) + " AGL at t="
				+ Units.num(apogee) + " s");
		flight.put("maxMach", Units.num(sim.getSimulatedData().getMaxMachNumber()));
		out.put("flight", flight);

		// Barometric lockout: no barometric event while the flow over the ports is transonic.
		double[] mach = br.col(FlightDataType.TYPE_MACH_NUMBER);
		double lastFast = Double.NaN;
		for (int i = 0; i < br.size() && br.time[i] <= apogee; i++) {
			if (mach[i] >= o.baroMach()) {
				lastFast = br.time[i];
			}
		}
		Map<String, Object> lock = new LinkedHashMap<>();
		List<String> warnings = new ArrayList<>();
		if (Double.isNaN(lastFast)) {
			lock.put("machLockout", "not needed: the flight stays below Mach " + Units.num(o.baroMach()));
		} else {
			double set = Math.ceil(lastFast + o.lockoutMargin());
			lock.put("machLockout", Units.num(set) + " s after launch (above Mach " + Units.num(o.baroMach()) + " until t="
					+ Units.num(lastFast) + " s, plus " + Units.num(o.lockoutMargin()) + " s)");
			if (set > apogee - 2) {
				warnings.add("The lockout runs to within 2 s of apogee (" + Units.num(apogee)
						+ " s): barometric apogee detection is marginal. Use an accelerometer-based apogee or a timer backup.");
			}
		}
		out.put("lockout", lock);

		// Channels per recovery device, primary and backup.
		List<Map<String, Object>> channels = new ArrayList<>();
		List<Sims.Deployment> deps = Sims.deployments(sim);
		for (RocketComponent c : sim.getRocket()) {
			if (!(c instanceof RecoveryDevice rd) || !sim.getRocket().getFlightConfiguration(fcid).isComponentActive(c)) {
				continue;
			}
			DeploymentConfiguration dc = rd.getDeploymentConfigurations().get(fcid);
			Map<String, Object> ch = new LinkedHashMap<>();
			ch.put("device", rd.getName());
			Sims.Deployment dep = null;
			for (Sims.Deployment x : deps) {
				// Events point at the simulated copy of the part: match by id.
				if (x.device().getID().equals(rd.getID()) && dep == null) {
					dep = x;
				}
			}
			// The branch the device opened in. Stages can share a name (OpenRocket's three-stage example has two
			// "Booster stage" branches), so look for the deployment event itself rather than the name.
			FlightDataBranch b = main;
			for (FlightDataBranch fb : sim.getSimulatedData().getBranches()) {
				if (fb.getEvents().stream().anyMatch(e -> e.getType() == FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT
						&& e.getSource() != null && e.getSource().getID().equals(rd.getID()))) {
					b = fb;
					break;
				}
			}
			DeployEvent ev = dc.getDeployEvent();
			double delay = dc.getDeployDelay();
			switch (ev) {
				case APOGEE -> {
					double bApogee = Sims.eventTime(b, FlightEvent.Type.APOGEE);
					double t1 = bApogee + delay, t2 = t1 + o.backupDelay();
					ch.put("primary", "apogee" + (delay > 0 ? " + " + Units.num(delay) + " s" : ""));
					ch.put("backup", "apogee + " + Units.num(delay + o.backupDelay()) + " s");
					ch.put("airspeedAtPrimary", Units.fmt(Sims.airspeed(b, t1), Dim.VELOCITY));
					ch.put("airspeedAtBackup", Units.fmt(Sims.airspeed(b, t2), Dim.VELOCITY)
							+ " (check the pins hold and the opening load with deployment_delay_sweep)");
				}
				case ALTITUDE -> {
					double h = dc.getDeployAltitude(), hb = h - o.backupMainOffset();
					ch.put("primary", Units.fmt(h, Dim.DISTANCE) + " AGL on the way down" + (delay > 0 ? " + " + Units.num(delay) + " s" : ""));
					ch.put("backup", Units.fmt(hb, Dim.DISTANCE) + " AGL on the way down");
					double tb = descendingThrough(b, hb);
					if (!Double.isNaN(tb)) {
						double tp = descendingThrough(b, h);
						ch.put("backupFiresAfterPrimaryBy", Units.fmt(tb - tp, Dim.TIME) + " (under the drogue)");
					}
					if (hb < 150) {
						warnings.add(rd.getName() + ": the backup at " + Units.fmt(hb, Dim.DISTANCE)
								+ " AGL leaves little time to open; raise the main or shrink the offset.");
					}
				}
				case EJECTION -> {
					ch.put("primary", "motor ejection charge");
					ch.put("backup", "altimeter at apogee + " + Units.num(o.backupDelay()) + " s");
					warnings.add(rd.getName() + " deploys on the motor's ejection charge; competition rules want electronic "
							+ "deployment with redundant altimeters (set_deployment event apogee).");
				}
				case LOWER_STAGE_SEPARATION -> {
					ch.put("primary", "stage separation (staging electronics)");
					ch.put("backup", "altimeter at apogee of that stage + " + Units.num(o.backupDelay()) + " s");
				}
				default -> ch.put("primary", ev.name().toLowerCase(Locale.ROOT).replace('_', ' '));
			}
			if (dep != null) {
				ch.put("simulated", "opens at t=" + Units.num(dep.time()) + " s, " + Units.fmt(dep.altitudeAgl(), Dim.DISTANCE)
						+ " AGL, " + Units.fmt(dep.airspeed(), Dim.VELOCITY) + " (" + dep.branch() + ")");
			}
			channels.add(ch);
		}
		if (channels.isEmpty()) {
			throw new ToolException("The design has no recovery devices to set altimeters for.");
		}
		out.put("channels", channels);
		Map<String, Object> alt = new LinkedHashMap<>();
		alt.put("primaryAltimeter", "every channel at its primary setting; Mach lockout as above");
		alt.put("backupAltimeter", "every channel at its backup setting, its own battery and switch, a different model "
				+ "if you can (one firmware bug cannot take out both)");
		out.put("altimeters", alt);
		if (bayVolume > 0) {
			out.put("staticPorts", Avionics.staticPorts(bayVolume) + (bayName == null ? "" : " (" + bayName + ")"));
		} else {
			out.put("staticPorts", "no avionics bay found: pass bay (the coupler) or bayVolume to size the ports");
		}
		if (!warnings.isEmpty()) {
			out.put("warnings", warnings);
		}
		out.put("notes", List.of("Altimeters measure height above the pad from pressure: power them on at the pad, after "
				+ "the bay is sealed, and let them settle before arming.",
				"Ground-test every channel with the flight batteries before launch day (pop tests).",
				"These settings follow this simulation; re-run after mass, motor or recovery changes."));
		if (card != null) {
			out.put("card", writeCard(card, sim, out).toString());
		}
		return out;
	}

	/** Time the branch first descends through {@code h} AGL after apogee, NaN when it does not. */
	static double descendingThrough(FlightDataBranch b, double h) {
		Branch br = Branch.of(b);
		double apogee = Sims.eventTime(b, FlightEvent.Type.APOGEE);
		double[] alt = br.col(FlightDataType.TYPE_ALTITUDE);
		for (int i = Math.max(1, br.index(Double.isNaN(apogee) ? 0 : apogee)); i < br.size(); i++) {
			if (alt[i - 1] >= h && alt[i] < h) {
				double f = (alt[i - 1] - h) / (alt[i - 1] - alt[i]);
				return br.time[i - 1] + f * (br.time[i] - br.time[i - 1]);
			}
		}
		return Double.NaN;
	}

	/** Free volume of the avionics bay: a coupler named like an av-bay, or the given component. */
	public static double bayVolume(RocketComponent c) {
		if (c instanceof RingComponent rc) {
			double r = rc.getInnerRadius();
			return Math.PI * r * r * rc.getLength();
		}
		return Components.interiorVolume(c, Double.NaN);
	}

	@SuppressWarnings("unchecked")
	private static Path writeCard(Path card, Simulation sim, Map<String, Object> out) throws IOException {
		Path p = card.toAbsolutePath();
		if (p.getParent() != null) {
			Files.createDirectories(p.getParent());
		}
		StringBuilder b = new StringBuilder("# Altimeter settings: ").append(sim.getRocket().getName()).append("\n\n");
		b.append("Simulation '").append(sim.getName()).append("'. ").append(((Map<String, Object>) out.get("flight")).get("apogee"))
				.append(", max Mach ").append(((Map<String, Object>) out.get("flight")).get("maxMach")).append(".\n\n");
		b.append("| Channel | Primary altimeter | Backup altimeter |\n|---|---|---|\n");
		for (Map<String, Object> ch : (List<Map<String, Object>>) out.get("channels")) {
			b.append("| ").append(ch.get("device")).append(" | ").append(ch.get("primary")).append(" | ")
					.append(ch.getOrDefault("backup", "")).append(" |\n");
		}
		b.append("\n**Mach lockout:** ").append(((Map<String, Object>) out.get("lockout")).get("machLockout")).append("\n\n");
		b.append("**Static ports:** ").append(out.get("staticPorts")).append("\n\n");
		if (out.containsKey("warnings")) {
			b.append("**Warnings**\n\n");
			for (String w : (List<String>) out.get("warnings")) {
				b.append("- ").append(w).append('\n');
			}
			b.append('\n');
		}
		b.append("**Before flight**\n\n");
		for (String s : List.of("Primary altimeter programmed and read back (beeps / app) - initials:",
				"Backup altimeter programmed and read back - initials:", "Batteries charged / new, voltage:",
				"Continuity on every channel, both altimeters:", "Ports clear, bay sealed:")) {
			b.append("- [ ] ").append(s).append(" ________\n");
		}
		Files.writeString(p, b.toString());
		return p;
	}
}
