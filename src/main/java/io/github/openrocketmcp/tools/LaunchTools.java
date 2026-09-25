package io.github.openrocketmcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Weather;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.report.FlightCard;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Launch day: weather forecast for the site and the flight card. */
public final class LaunchTools {
	private LaunchTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("weather_forecast", "Launch-site wind and weather forecast",
				"Get the forecast for the launch site from Open-Meteo (free, no key) by GPS coordinates: ground wind, gusts, "
						+ "temperature and pressure, and wind at altitude up to the jet stream (pressure levels). By default it is "
						+ "applied to the design's simulation (wind profile, site altitude, temperature, pressure) so every tool flies "
						+ "the forecast, and the flight is re-simulated. Ask the user for the site's latitude / longitude if the team "
						+ "standards do not have them. Without internet access, pass the Open-Meteo JSON as forecastJson, or enter "
						+ "winds by hand with wind_profile.",
				SimTools.simSelect(Schema.object())
						.num("latitude", "Launch site latitude, decimal degrees (default: launchSite.latitude).", false)
						.num("longitude", "Launch site longitude, decimal degrees (default: launchSite.longitude).", false)
						.str("time", "Launch time in the site's local time, e.g. \"2027-08-21T10:00\" (default: next hour).", false)
						.qty("top", "Highest wind level to use (default 12 km).", false)
						.bool("apply", "Apply to the simulation and re-simulate (default true; needs an open design).", false)
						.str("forecastJson", "Open-Meteo hourly JSON, if the server has no internet access.", false).build(),
				false, a -> {
					var std = ctx.standards();
					double lat = a.num("latitude", std.q("launchSite.latitude", Dim.DIMENSIONLESS, Double.NaN));
					double lon = a.num("longitude", std.q("launchSite.longitude", Dim.DIMENSIONLESS, Double.NaN));
					JsonObject json;
					if (a.has("forecastJson")) {
						json = JsonParser.parseString(a.str("forecastJson")).getAsJsonObject();
						if (Double.isNaN(lat) && json.has("latitude")) {
							lat = json.get("latitude").getAsDouble();
							lon = json.get("longitude").getAsDouble();
						}
					} else {
						if (Double.isNaN(lat) || Double.isNaN(lon)) {
							throw new ToolException("I need the launch site's GPS coordinates: give latitude and longitude in decimal "
									+ "degrees (e.g. 48.47, -81.33), or set launchSite.latitude / longitude in the team standards.");
						}
						json = Weather.fetch(lat, lon);
					}
					Weather.Forecast f = Weather.parse(json, a.str("time", null));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("site", Units.num(lat) + ", " + Units.num(lon) + " (forecast grid elevation " + Units.fmt(f.elevation(), Dim.DISTANCE) + ")");
					out.put("time", f.time() + " (" + f.timezone() + ")");
					Map<String, Object> ground = new LinkedHashMap<>();
					ground.put("wind", Units.fmt(f.speed(), Dim.VELOCITY) + " (" + Units.num(f.speed() * 3.6) + " km/h) from "
							+ Units.num(Math.toDegrees(f.direction())) + " deg");
					if (!Double.isNaN(f.gust())) {
						ground.put("gusts", Units.fmt(f.gust(), Dim.VELOCITY) + " (" + Units.num(f.gust() * 3.6) + " km/h)");
					}
					if (!Double.isNaN(f.temperature())) {
						ground.put("temperature", Units.fmt(f.temperature(), Dim.TEMPERATURE));
					}
					if (!Double.isNaN(f.pressure())) {
						ground.put("pressure", Units.fmt(f.pressure(), Dim.PRESSURE));
					}
					double maxWind = std.rule("maxGroundWind.value", Dim.VELOCITY);
					if (!Double.isNaN(maxWind)) {
						double worst = Double.isNaN(f.gust()) ? f.speed() : Math.max(f.speed(), f.gust());
						ground.put("vsRuleLimit", (f.speed() <= maxWind ? "mean wind within " : "mean wind ABOVE ")
								+ Units.num(maxWind * 3.6) + " km/h limit" + (worst > maxWind && f.speed() <= maxWind
										? "; gusts exceed it" : ""));
					}
					out.put("ground", ground);
					double top = a.qty("top", Dim.DISTANCE, 12000);
					List<String> aloft = new ArrayList<>();
					for (Winds.Level l : f.levels()) {
						if (l.altitude() <= top) {
							aloft.add(Units.fmt(l.altitude(), Dim.DISTANCE) + " AGL: " + Units.fmt(l.speed(), Dim.VELOCITY) + " from "
									+ Units.num(Math.toDegrees(l.direction())) + " deg");
						}
					}
					out.put("windsAloft", aloft);
					if (a.bool("apply", true) && !ctx.designs.all().isEmpty()) {
						Designs.Design d = ctx.designs.get(a.str("designId", null));
						Simulation sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), std);
						Weather.apply(sim.getOptions(), f, top, lat, lon);
						d.doc.setSaved(false);
						Sims.run(sim);
						Map<String, Object> fl = new LinkedHashMap<>(io.github.openrocketmcp.or.Sims.flightMetrics(sim.getSimulatedData()));
						out.put("flightInForecast", fl);
						out.put("applied", "wind profile, site altitude, temperature and pressure set on '" + sim.getName()
								+ "'; every tool now flies this forecast. Next: monte_carlo for the landing area, check_requirements, flight_card.");
					}
					out.put("source", "Open-Meteo forecast (open-meteo.com); winds aloft at pressure levels by geopotential height. "
							+ "Forecasts carry uncertainty: re-check on the day and use monte_carlo for dispersion.");
					return out;
				}));

		s.tool(new ToolDef("flight_card", "Launch-day flight card",
				"Write a one-page flight card (Markdown) from the simulation in the current conditions (apply weather_forecast "
						+ "first for the day's winds): vehicle, CG / CP and stability, motors with the optimum ejection delay and the "
						+ "closest available delay, predicted flight, recovery settings (events, deployment altitudes, descent "
						+ "rates), sections and landing energy, drift for each ground wind, the rule check, and sign-off lines.",
				SimTools.simSelect(Schema.object()).str("path", "Output .md path (default flight-card.md).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation sim = SimTools.runSelected(ctx, a);
					var o = sim.getOptions();
					var std = ctx.standards();
					boolean coords = Winds.isMultiLevel(o) || !Double.isNaN(std.q("launchSite.latitude", Dim.DIMENSIONLESS, Double.NaN));
					String site = (coords ? Units.num(o.getLaunchLatitude()) + ", " + Units.num(o.getLaunchLongitude()) + ", "
							: "coordinates not set (weather_forecast or launchSite in the team standards), ")
							+ Units.fmt(o.getLaunchAltitude(), Dim.DISTANCE) + " MSL";
					return FlightCard.write(d, sim, ctx.standards(), Path.of(a.str("path", "flight-card.md")), site);
				}));
	}
}
