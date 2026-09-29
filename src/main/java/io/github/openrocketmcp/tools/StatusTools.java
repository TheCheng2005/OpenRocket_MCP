package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.Transition;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** "Where are we?": readiness of a design and the next steps, in order. */
public final class StatusTools {
	private StatusTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("design_status", "Where the design stands and what to do next",
				"A one-call overview for the start of a session or a design review: the rule check in short (failures and "
						+ "warnings), what is not set up yet (motor, recovery deployment, electronics, RASAero data where the "
						+ "airframe changes diameter, the team's launch site and standards), mass overrides that hide edits, "
						+ "unsaved changes and edits that can be undone, then the next steps in order with the tool for each. "
						+ "Use it first when someone opens a design or asks what is left to do.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).str("configuration", DesignTools.CONFIG, false).build(),
				false, a -> status(ctx, a.str("designId", null), a.str("configuration", null))));
	}

	/** One line of the overview. */
	record Item(String area, String status, String finding, String next, int priority) {
		Map<String, Object> render() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("area", area);
			m.put("status", status);
			m.put("finding", finding);
			if (next != null) {
				m.put("next", next);
			}
			return m;
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> status(Context ctx, String designId, String configuration) {
		Designs.Design d = ctx.designs.get(designId);
		FlightConfiguration fc = Components.config(d.doc.getRocket(), configuration);
		var std = ctx.standards();
		List<Item> items = new ArrayList<>();

		// Vehicle.
		Map<String, Object> vehicle = new LinkedHashMap<>();
		vehicle.put("name", d.name());
		vehicle.put("file", d.path == null ? "not saved to a file yet" : d.path.toString());
		Analysis.Stability st = Analysis.stability(fc, 0.3);
		vehicle.put("length", Units.fmt(fc.getLength(), Dim.LENGTH));
		vehicle.put("launchMass", Units.fmt(st.launchMass(), Dim.MASS));
		vehicle.put("stabilityAtLaunch", Units.num(st.marginCalibers()) + " cal (static, Mach 0.3)");
		vehicle.put("stages", fc.getActiveStages().size());

		// Motor.
		boolean motor = fc.hasMotors();
		if (!motor) {
			items.add(new Item("Motor", "TODO", "No motor in the active configuration, so nothing can be flown yet.",
					"Pick one: rank_motors (target apogee or smallest that meets the rules), then set_motor.", 0));
		}

		// Recovery set-up.
		int devices = 0, deploying = 0;
		boolean electronics = false;
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (c instanceof RecoveryDevice rd) {
				devices++;
				var dc = rd.getDeploymentConfigurations().get(fc.getId());
				if (dc != null && dc.getDeployEvent() != null && dc.getDeployEvent() != DeploymentConfiguration.DeployEvent.NEVER) {
					deploying++;
				}
			}
			if (c instanceof MassComponent m && (m.getMassComponentType() == MassComponent.MassComponentType.ALTIMETER
					|| m.getMassComponentType() == MassComponent.MassComponentType.FLIGHTCOMPUTER)) {
				electronics = true;
			}
		}
		if (devices == 0) {
			items.add(new Item("Recovery", "TODO", "No parachute or streamer in the design.",
					"size_parachute for the main and drogue, then add_component (Parachute) and set_deployment.", 1));
		} else if (deploying < devices) {
			items.add(new Item("Recovery", "TODO", (devices - deploying) + " of " + devices + " recovery devices never deploy in this "
					+ "configuration.", "set_deployment (e.g. drogue at apogee, main at 1000 ft).", 1));
		}
		if (!electronics) {
			items.add(new Item("Electronics", "TODO", "No altimeters or flight computers in the design, so its mass and layout "
					+ "are missing and the electronics rules cannot be checked.",
					"add_avionics_bay (two altimeter circuits, tracker, charges), with your parts' masses.", 2));
		}

		// Rule check (needs a motor).
		Map<String, Object> rules = null;
		if (motor) {
			try {
				Simulation sim = Sims.prepare(d, null, fc.getId().toString(), Sims.Overrides.none(), std);
				Sims.run(sim);
				Simulation wind = null;
				double maxWind = std.rule("maxGroundWind.value", Dim.VELOCITY);
				if (!Double.isNaN(maxWind)) {
					wind = sim.copy();
					Winds.setGround(wind.getOptions(), maxWind, Double.NaN);
					Sims.run(wind);
				}
				rules = Requirements.check(sim, wind, std).render(std.rulesName());
				vehicle.put("apogee", Units.fmt(sim.getSimulatedData().getMaxAltitude(), Dim.DISTANCE));
				vehicle.put("maxMach", Units.num(sim.getSimulatedData().getMaxMachNumber()));
				for (Map<String, Object> c : (List<Map<String, Object>>) rules.get("checks")) {
					String status = String.valueOf(c.get("status"));
					if (!status.equals("FAIL") && !status.equals("WARN")) {
						continue;
					}
					String item = String.valueOf(c.get("item"));
					String finding = item + ": " + c.get("value") + (c.containsKey("ref") ? " (" + c.get("ref") + ")" : "");
					items.add(new Item(area(item), status, finding, fix(item), status.equals("FAIL") ? -1 : 5));
				}
			} catch (RuntimeException e) {
				items.add(new Item("Flight", "FAIL", "The simulation fails: " + e.getMessage(),
						"get_design to check the configuration; fix the component or motor named in the error.", -2));
			}
		}

		// Aero data where Launch Canada expects it.
		boolean diameterChange = false;
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (c instanceof Transition t && !(c instanceof NoseCone)
					&& Math.abs(t.getForeRadius() - t.getAftRadius()) > 1e-4) {
				diameterChange = true;
			}
		}
		AeroTable.Table table = AeroTable.of(d.doc.getRocket());
		if (diameterChange && table == null) {
			items.add(new Item("Aerodynamics", "TODO", "The airframe changes diameter and no RASAero / CFD data is imported; Launch "
					+ "Canada expects RASAero CP and CD for such airframes.", "import_aero_table (RASAero export, or CFD results from "
							+ "export_geometry's run matrix).", 4));
		} else if (table != null) {
			items.add(new Item("Aerodynamics", "OK", "Imported aero data in use: " + table.source() + ".", null, 9));
		}

		// Team standards.
		if (std.source() == null && !std.edited()) {
			items.add(new Item("Standards", "TODO", "Using the built-in defaults, not your team's standards (safety factors, shear "
					+ "pins, fin materials, launch site).", "load_standards or update_standards (e.g. launchSite.altitudeMsl, "
							+ "your fin laminate's shear modulus).", 6));
		} else if (std.edited() && std.source() == null) {
			items.add(new Item("Standards", "TODO", "The team standards were changed in this session but are not saved to a file, "
					+ "so the next session and the rest of the team will not have them.", "update_standards with saveTo "
							+ "(e.g. openrocket-mcp.json), then commit the file.", 6));
		}

		// Mass overrides that hide edits.
		for (RocketComponent c : fc.getAllActiveComponents()) {
			String w = Components.overrideWarning(c);
			if (w != null && c.isMassOverridden() && c.isSubcomponentsOverriddenMass()) {
				items.add(new Item("Mass", "INFO", w, "Update the override after weighing, or remove it while designing.", 7));
			}
		}

		// Session state.
		int undo = d.history == null ? 0 : d.history.undoable();
		if (!d.doc.isSaved()) {
			items.add(new Item("File", "TODO", "Unsaved changes" + (undo > 0 ? " (" + undo + " edit(s) this session, undo available)" : "")
					+ ".", "save_design once the team agrees (history shows what changed).", 8));
		}

		items.sort((x, y) -> Integer.compare(x.priority(), y.priority()));
		List<Map<String, Object>> rows = new ArrayList<>();
		List<String> next = new ArrayList<>();
		int fails = 0, todo = 0, warns = 0;
		for (Item i : items) {
			rows.add(i.render());
			switch (i.status()) {
				case "FAIL" -> fails++;
				case "TODO" -> todo++;
				case "WARN" -> warns++;
				default -> {
				}
			}
			if (i.next() != null && !i.status().equals("OK") && !next.contains(i.next()) && next.size() < 8) {
				next.add(i.next());
			}
		}
		if (next.isEmpty()) {
			next.add("Before launch day: monte_carlo with your uncertainties, then weather_forecast and flight_card.");
			next.add("For the review board: generate_report, fin_fea on the fins, compare_designs against the last review.");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("readiness", fails > 0 ? "Not ready: " + fails + " rule failure(s)" + (todo > 0 ? " and " + todo + " thing(s) to set up" : "")
				: todo > 0 ? "In progress: " + todo + " thing(s) to set up" + (warns > 0 ? ", " + warns + " warning(s)" : "")
						: warns > 0 ? "No failures; " + warns + " warning(s) to review" : "Meets every automated check");
		out.put("vehicle", vehicle);
		if (rules != null) {
			out.put("ruleCheck", rules.get("ruleSet") + ": " + rules.get("summary"));
		}
		out.put("items", rows);
		out.put("nextSteps", next);
		out.put("note", "Automated checks only: the manual checklist (check_requirements), ground tests and your RSO still apply.");
		return out;
	}

	static String area(String item) {
		String i = item.toLowerCase(Locale.ROOT);
		if (i.contains("stab") || i.contains("margin") || i.contains("damping") || i.contains("l:d")) {
			return i.contains("flutter") ? "Fins" : "Stability";
		}
		if (i.contains("flutter")) {
			return "Fins";
		}
		if (i.contains("rail") || i.contains("thrust") || i.contains("launch angle")) {
			return "Launch";
		}
		if (i.contains("descent") || i.contains("drogue") || i.contains("main") || i.contains("deploy") || i.contains("dual")
				|| i.contains("recovery")) {
			return "Recovery";
		}
		if (i.contains("electronic") || i.contains("altimeter") || i.contains("power") || i.contains("avionics")) {
			return "Electronics";
		}
		if (i.contains("site") || i.contains("altitude")) {
			return "Launch site";
		}
		return "Rules";
	}

	/** The tool that usually fixes a failing check. */
	static String fix(String item) {
		String i = item.toLowerCase(Locale.ROOT);
		if (i.contains("flutter")) {
			return "fin_flutter for the thickness needed, then optimize_fins with your stock thicknesses.";
		}
		if (i.contains("over-stab") || i.contains("overstab") || (i.contains("stab") && i.contains("max"))) {
			return "optimize_fins (smaller fins) or less nose ballast.";
		}
		if (i.contains("stab") || i.contains("margin") || i.contains("damping")) {
			return "ballast (nose weight) or optimize_fins; check in the design wind.";
		}
		if (i.contains("rail")) {
			return "rank_motors (more initial thrust) or a longer rail (railLength).";
		}
		if (i.contains("thrust")) {
			return "rank_motors with minTwr, or reduce mass.";
		}
		if (i.contains("descent") || i.contains("drogue") || i.contains("main")) {
			return "size_parachute for the target descent rate, then edit_components.";
		}
		if (i.contains("deploy") || i.contains("dual")) {
			return "set_deployment (and deployment_delay_sweep for the drogue).";
		}
		if (i.contains("electronic") || i.contains("power") || i.contains("altimeter")) {
			return "add_avionics_bay, or add the missing altimeter / battery.";
		}
		if (i.contains("site")) {
			return "update_standards launchSite.altitudeMsl (and weather_forecast on the day).";
		}
		if (i.contains("l:d")) {
			return "Shorten the airframe or increase the diameter.";
		}
		if (i.contains("rail button")) {
			return "Use non-metallic rail buttons (edit_components material).";
		}
		return "check_requirements for the details and rule reference.";
	}
}
