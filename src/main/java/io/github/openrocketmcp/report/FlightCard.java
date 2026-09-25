package io.github.openrocketmcp.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sections;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** A one-page launch-day flight card (Markdown) from the simulated flight in the day's conditions. */
public final class FlightCard {
	private FlightCard() {
	}

	/** Motor ejection-delay advice: optimum = apogee time - burnout time of that motor. */
	static Map<String, Object> delayAdvice(Simulation sim, MotorConfiguration mc) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		double apogee = Double.NaN, burnout = Double.NaN;
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() == FlightEvent.Type.APOGEE && Double.isNaN(apogee)) {
				apogee = e.getTime();
			}
			if (e.getType() == FlightEvent.Type.BURNOUT && e.getSource() != null
					&& e.getSource().getID().equals(((RocketComponent) mc.getMount()).getID())) {
				burnout = e.getTime();
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		if (Double.isNaN(apogee) || Double.isNaN(burnout)) {
			return m;
		}
		double opt = apogee - burnout;
		m.put("optimumDelay", Units.fmt(opt, Dim.TIME));
		double cd = mc.getEjectionDelay();
		m.put("configuredDelay", Double.isNaN(cd) || Double.isInfinite(cd) ? "plugged" : Units.fmt(cd, Dim.TIME));
		if (mc.getMotor() instanceof ThrustCurveMotor t && t.getStandardDelays() != null && t.getStandardDelays().length > 0) {
			double best = Double.NaN;
			List<String> all = new ArrayList<>();
			for (double d : t.getStandardDelays()) {
				all.add(Units.num(d));
				if (Double.isNaN(best) || Math.abs(d - opt) < Math.abs(best - opt)) {
					best = d;
				}
			}
			m.put("availableDelays", String.join(", ", all) + " s");
			m.put("closestAvailable", Units.fmt(best, Dim.TIME));
		}
		m.put("advice", "With electronic deployment, use the motor charge as a backup a little after apogee (optimum + 1-2 s, "
				+ "drilled if needed) or plug it; never before apogee.");
		return m;
	}

	/** One-line wind summary: the single wind, or the profile's levels up to ~3 km. */
	static String windLine(info.openrocket.core.simulation.SimulationOptions o) {
		if (!Winds.isMultiLevel(o)) {
			return Units.fmt(Winds.speed(o), Dim.VELOCITY) + " from " + Units.num(Math.toDegrees(Winds.direction(o))) + " deg";
		}
		List<String> parts = new ArrayList<>();
		for (Winds.Level l : Winds.levels(o)) {
			if (l.altitude() == 0 || l.altitude() >= 100 && l.altitude() <= 3500) {
				parts.add(Units.fmt(l.altitude(), Dim.DISTANCE) + ": " + Units.fmt(l.speed(), Dim.VELOCITY) + " from "
						+ Units.num(Math.toDegrees(l.direction())) + " deg");
			}
		}
		return "profile (AGL) " + String.join("; ", parts);
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> write(Designs.Design d, Simulation sim, Standards std, Path file, String site)
			throws IOException {
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		FlightData data = sim.getSimulatedData();
		Analysis.Stability st = Analysis.stability(fc, 0.3);
		Map<String, Object> summary = Sims.summarize(sim);
		Map<String, Object> flight = (Map<String, Object>) summary.get("flight");
		StringBuilder md = new StringBuilder();
		md.append("# Flight card: ").append(d.name()).append("\n\n");
		md.append("| | |\n|---|---|\n");
		md.append("| Configuration | ").append(fc.getName()).append(" |\n");
		md.append("| Date | ").append(LocalDate.now()).append(" |\n");
		if (site != null) {
			md.append("| Site / forecast | ").append(site).append(" |\n");
		}
		md.append("| Rule set | ").append(std.rulesName()).append(" |\n\n");

		md.append("## Vehicle\n\n| | |\n|---|---|\n");
		md.append("| Length / diameter | ").append(Units.fmt(st.length(), Dim.LENGTH)).append(" / ")
				.append(Units.fmt(st.referenceDiameter(), Dim.LENGTH)).append(" |\n");
		md.append("| Launch mass | ").append(Units.fmt(st.launchMass(), Dim.MASS)).append(" |\n");
		md.append("| CG (launch) / CP | ").append(Units.fmt(st.cgLaunchX(), Dim.LENGTH)).append(" / ")
				.append(Units.fmt(st.cpX(), Dim.LENGTH)).append(" from the nose tip |\n");
		md.append("| Static stability (launch / burnout) | ").append(Units.num(st.marginCalibers())).append(" / ")
				.append(Units.num(st.burnoutMarginCalibers())).append(" cal |\n");
		Sims.Window w = Sims.ascentStability(data.getBranch(0));
		if (w != null) {
			md.append("| Minimum stability in flight | ").append(Units.num(w.min())).append(" cal |\n");
		}
		md.append('\n');

		md.append("## Motors\n\n");
		List<Map<String, Object>> motors = new ArrayList<>();
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() == null) {
				continue;
			}
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("mount", ((RocketComponent) mc.getMount()).getName());
			r.put("motor", mc.getMotor().getDesignation() + (mc.getMotorCount() > 1 ? " x" + mc.getMotorCount() : ""));
			r.put("ignition", mc.toIgnitionDescription());
			r.putAll(delayAdvice(sim, mc));
			r.remove("advice");
			motors.add(r);
		}
		md.append(Reports.table(motors)).append('\n');
		md.append("Motor ejection: with electronic deployment use the motor charge as a backup a little after apogee "
				+ "(optimum + 1-2 s) or plug it; never before apogee.\n\n");

		md.append("## Predicted flight\n\n| | |\n|---|---|\n");
		String[][] rows = { { "apogee", "Apogee" }, { "timeToApogee", "Time to apogee" }, { "maxVelocity", "Max velocity" },
				{ "maxMach", "Max Mach" }, { "maxAcceleration", "Max acceleration" }, { "railExitVelocity", "Rail exit velocity" },
				{ "thrustToWeightOnRail(avg)", "Thrust-to-weight" } };
		for (String[] k : rows) {
			if (flight.containsKey(k[0])) {
				md.append("| ").append(k[1]).append(" | ").append(flight.get(k[0])).append(" |\n");
			}
		}
		md.append("| Wind | ").append(windLine(sim.getOptions())).append(" |\n\n");

		md.append("## Recovery settings\n\n");
		List<Map<String, Object>> rec = new ArrayList<>();
		for (RocketComponent c : fc.getActiveComponents()) {
			if (c instanceof RecoveryDevice rd) {
				DeploymentConfiguration dc = rd.getDeploymentConfigurations().get(fc.getId());
				Map<String, Object> r = new LinkedHashMap<>();
				r.put("device", rd.getName());
				r.put("stage", rd.getStage().getName());
				r.put("event", dc.getDeployEvent().name().toLowerCase());
				if (dc.getDeployEvent() == DeploymentConfiguration.DeployEvent.ALTITUDE) {
					r.put("altitude", Units.fmt(dc.getDeployAltitude(), Dim.DISTANCE));
				}
				if (dc.getDeployDelay() > 0) {
					r.put("delay", Units.fmt(dc.getDeployDelay(), Dim.TIME));
				}
				for (Sims.Deployment dep : Sims.deployments(sim)) {
					if (dep.device().getID().equals(rd.getID())) {
						r.put("deploysAt", Units.fmt(dep.altitudeAgl(), Dim.DISTANCE) + " AGL, " + Units.fmt(dep.airspeed(), Dim.VELOCITY));
						r.put("descentRate", Units.fmt(dep.steadyDescentRate(), Dim.VELOCITY));
					}
				}
				rec.add(r);
			}
		}
		md.append(Reports.table(rec)).append('\n');

		Map<String, Object> sections = Sections.analyze(sim, std, null);
		List<Map<String, Object>> secRows = new ArrayList<>();
		for (Map<String, Object> s : (List<Map<String, Object>>) sections.get("sections")) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("section", s.get("section"));
			r.put("mass", s.get("landingMass"));
			r.put("landing energy", s.get("landingEnergy"));
			secRows.add(r);
		}
		md.append("### Sections\n\n").append(Reports.table(secRows)).append('\n');

		md.append("## Drift vs ground wind\n\n").append(Reports.table(Reports.windTable(d, sim, std))).append('\n');

		Map<String, Object> checks = Requirements.check(sim, null, std).render(std.rulesName());
		md.append("## Rule check\n\n").append(checks.get("summary")).append("\n\n");
		for (Map<String, Object> c : (List<Map<String, Object>>) checks.get("checks")) {
			if ("FAIL".equals(c.get("status"))) {
				md.append("- FAIL: ").append(c.get("item")).append(" (").append(c.get("value")).append(")\n");
			}
		}
		md.append("\n## Sign-off\n\n| Role | Name | Signature |\n|---|---|---|\n| Team lead | | |\n| Recovery lead | | |\n"
				+ "| RSO | | |\n\nPredictions are OpenRocket simulations in the conditions above; they do not replace the RSO's review.\n");

		Path p = file.toAbsolutePath();
		if (p.getParent() != null) {
			Files.createDirectories(p.getParent());
		}
		Files.writeString(p, md.toString(), StandardCharsets.UTF_8);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("file", p.toString());
		out.put("apogee", flight.get("apogee"));
		out.put("railExitVelocity", flight.get("railExitVelocity"));
		out.put("motors", motors);
		out.put("ruleCheck", checks.get("summary"));
		return out;
	}
}
