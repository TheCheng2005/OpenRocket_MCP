package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Running simulations, flight data, sweeps and rule checks. */
public final class SimTools {
	private SimTools() {
	}

	static Schema overrides(Schema s) {
		return s.qty("windSpeed", "Average wind speed, e.g. \"30 km/h\".", false)
				.qty("windDirection", "Wind direction (from), e.g. \"270 deg\".", false)
				.num("windTurbulence", "Turbulence intensity 0-1 (e.g. 0.1).", false)
				.qty("railLength", "Launch rail length.", false)
				.qty("launchAngle", "Launch rail angle FROM VERTICAL, e.g. \"6 deg\" (= 84 deg elevation).", false)
				.qty("launchDirection", "Rail direction, e.g. \"90 deg\".", false)
				.bool("launchIntoWind", "Aim the rail into the wind.", false)
				.qty("launchSiteAltitude", "Launch site altitude above sea level.", false)
				.num("latitude", "Launch site latitude (deg).", false)
				.num("longitude", "Launch site longitude (deg).", false)
				.qty("temperature", "Ground temperature, e.g. \"30 C\".", false);
	}

	static Sims.Overrides overrides(Args a) {
		Boolean intoWind = a.has("launchIntoWind") ? a.bool("launchIntoWind", true) : null;
		return new Sims.Overrides(a.qtyOrNaN("windSpeed", Dim.VELOCITY), a.qtyOrNaN("windDirection", Dim.ANGLE),
				a.num("windTurbulence", Double.NaN), a.qtyOrNaN("railLength", Dim.LENGTH), a.qtyOrNaN("launchAngle", Dim.ANGLE),
				a.qtyOrNaN("launchDirection", Dim.ANGLE), a.qtyOrNaN("launchSiteAltitude", Dim.DISTANCE),
				a.num("latitude", Double.NaN), a.num("longitude", Double.NaN), a.qtyOrNaN("temperature", Dim.TEMPERATURE),
				intoWind, Double.NaN);
	}

	static Schema simSelect(Schema s) {
		return s.str("designId", DesignTools.DESIGN_ID, false)
				.str("simulation", "Simulation index or name. Default: the first simulation of the configuration; one is "
						+ "created with the team's launch-site defaults if none exists.", false)
				.str("configuration", DesignTools.CONFIG, false);
	}

	/** Prepares and runs the selected simulation with overrides from the arguments. */
	static Simulation runSelected(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), overrides(a), ctx.standards());
		Sims.run(sim);
		return sim;
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("run_simulation", "Simulate a flight",
				"Run an OpenRocket 6-DOF simulation and return a compact summary: apogee, max velocity/Mach/acceleration, rail exit "
						+ "velocity, thrust-to-weight, stability at rail exit and min/max during ascent, each stage's events, "
						+ "ignitions (tilt and altitude for air starts), every recovery deployment (airspeed, altitude, density, mass, "
						+ "steady descent rate) and landing distance per stage. Overrides apply to this run only.",
				overrides(simSelect(Schema.object())).build(), false, a -> Sims.summarize(runSelected(ctx, a))));

		s.tool(new ToolDef("get_flight_data", "Get time series from a simulation",
				"Down-sampled time series for plotting or inspection (e.g. stability vs time for the design review plots). "
						+ "Variables are OpenRocket flight data names such as altitude, velocitytotal, verticalvelocity (velocityz), "
						+ "accelerationtotal, machnumber, stability, cglocation, cplocation, thrustforce, dragforce, mass, aoa, "
						+ "airdensity, orientationtheta. Runs the simulation first.",
				overrides(simSelect(Schema.object()))
						.array("variables", "Flight variables to include (time is always included).", Schema.type("string", null), true)
						.str("branch", "Stage branch name or index (default 0 = sustainer / main vehicle).", false)
						.integer("maxPoints", "Maximum rows (default 60, max 500).", false)
						.qty("tStart", "Start time.", false).qty("tEnd", "End time.", false).build(),
				false, a -> {
					Simulation sim = runSelected(ctx, a);
					return Sims.series(sim, a.str("branch", null), a.strList("variables"),
							Math.min(500, a.integer("maxPoints", 60)), a.qtyOrNaN("tStart", Dim.TIME), a.qtyOrNaN("tEnd", Dim.TIME));
				}));

		s.tool(new ToolDef("sweep", "Parameter sweep",
				"Run the simulation for several values of one parameter and tabulate the results. parameter is either a launch "
						+ "condition (windSpeed, launchAngle, railLength, launchSiteAltitude, temperature) or a component property "
						+ "(give component + property, e.g. fin \"height\" or a MassComponent \"componentMass\"). Component values "
						+ "are restored afterwards. Use it to explore trade-offs such as fin span vs stability vs apogee, or ballast "
						+ "vs margin.",
				simSelect(Schema.object())
						.str("parameter", "Launch condition name, or a component property name when component is given.", true)
						.str("component", "Component id/name for a property sweep.", false)
						.array("values", "Values to try (numbers in SI or strings with units).", Schema.quantityItem(), true).build(),
				true, a -> sweep(ctx, a)));

		s.tool(new ToolDef("check_requirements", "Check the design against competition rules",
				"Simulate and check against the active rule set (default Launch Canada 2027 = DTEG R4 + 2027 edicts) and team "
						+ "standards: launch angle, rail departure velocity, thrust-to-weight (per stage), ascent stability and "
						+ "over-stability (also in the design wind), L:D ratio, damping ratio, static margin as % of body length, "
						+ "rail button material, SRAD engine Isp, air-start tilt and altitude inhibit, early/late deployments, "
						+ "dual-event recovery, drogue and main descent rates, main deployment altitude, and the dress-rehearsal pop "
						+ "tests to plan. Returns PASS/FAIL/WARN/INFO with rule references plus a manual checklist (electronics, "
						+ "radio, structures, operations).",
				overrides(simSelect(Schema.object()))
						.bool("includeWindCase", "Also simulate at the rule set's maximum ground wind (default true).", false).build(),
				false, a -> {
					Simulation sim = runSelected(ctx, a);
					Simulation wind = null;
					double maxWind = ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY);
					if (a.bool("includeWindCase", true) && !Double.isNaN(maxWind)) {
						wind = sim.copy();
						wind.getOptions().setWindSpeedAverage(maxWind);
						Sims.run(wind);
					}
					return Requirements.check(sim, wind, ctx.standards()).render(ctx.standards().rulesName());
				}));
	}

	private static Object sweep(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		String param = a.str("parameter");
		RocketComponent comp = a.has("component") ? Components.find(d.doc.getRocket(), a.str("component")) : null;
		com.google.gson.JsonArray values = a.array("values");
		if (values.isEmpty()) {
			throw new ToolException("values must not be empty.");
		}
		if (comp != null) {
			Components.getRaw(comp, param); // validates the property name up front
		}
		Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
				ctx.standards(), false);
		String compId = comp == null ? null : comp.getID().toString();
		List<Simulation> variants = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (JsonElement v : values) {
			if (comp != null) {
				String[] label = new String[1];
				variants.add(Variants.of(base, d.doc, r -> label[0] = Components.set(Components.find(r, compId), param, v), null));
				labels.add(label[0]);
			} else {
				com.google.gson.JsonObject one = a.raw().deepCopy();
				one.add(param, v);
				variants.add(Variants.of(base, d.doc, null, overrides(new Args(one))));
				labels.add(v.isJsonPrimitive() ? v.getAsString() : v.toString());
			}
		}
		List<Variants.Run> runs = Variants.runAll(variants);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put(param, labels.get(i));
			Variants.Run run = runs.get(i);
			if (!run.ok()) {
				row.put("error", run.error());
			} else {
				FlightData data = run.sim().getSimulatedData();
				row.putAll(Sims.flightMetrics(data));
				if (comp != null) {
					row.put("launchMass", Units.fmt(data.getBranch(0).getByIndex(FlightDataType.TYPE_MASS, 0), Dim.MASS));
				}
			}
			rows.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("parameter", comp == null ? param : comp.getName() + "." + param);
		out.put("results", rows);
		return out;
	}
}
