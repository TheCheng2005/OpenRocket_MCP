package io.github.openrocketmcp.or;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.mcp.ToolException;

/**
 * Wind and atmosphere forecast for a launch site from Open-Meteo (https://open-meteo.com, free, no key): surface wind,
 * gusts, temperature and pressure, plus wind at pressure levels (with their geopotential heights) up to the jet stream.
 * The base URL can be changed with the OPENROCKET_MCP_WEATHER_URL environment variable or the
 * openrocketmcp.weatherUrl system property (e.g. a mirror, or a local server in tests).
 */
public final class Weather {
	private Weather() {
	}

	public static final int[] PRESSURE_LEVELS = { 1000, 975, 950, 925, 900, 850, 800, 700, 600, 500, 400, 300, 250, 200,
			150, 100 };
	static final int[] NEAR_SURFACE = { 80, 120, 180 };

	/** A forecast for one hour: SI (m, m/s, rad FROM, K, Pa); levels AGL, lowest first. */
	public record Forecast(double latitude, double longitude, double elevation, String time, String timezone, double speed,
			double direction, double gust, double temperature, double pressure, List<Winds.Level> levels) {
	}

	public static String baseUrl() {
		String p = System.getProperty("openrocketmcp.weatherUrl");
		if (p != null && !p.isBlank()) {
			return p;
		}
		String e = System.getenv("OPENROCKET_MCP_WEATHER_URL");
		return e != null && !e.isBlank() ? e : "https://api.open-meteo.com/v1/forecast";
	}

	/** The request URL for a site (hourly values; wind in m/s; local time zone of the site). */
	public static String url(double lat, double lon) {
		StringBuilder v = new StringBuilder("wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m,surface_pressure");
		for (int h : NEAR_SURFACE) {
			v.append(",wind_speed_").append(h).append("m,wind_direction_").append(h).append('m');
		}
		for (int p : PRESSURE_LEVELS) {
			v.append(",wind_speed_").append(p).append("hPa,wind_direction_").append(p).append("hPa,geopotential_height_")
					.append(p).append("hPa");
		}
		return String.format(Locale.ROOT, "%s?latitude=%.5f&longitude=%.5f&hourly=%s&wind_speed_unit=ms&timezone=auto&forecast_days=16",
				baseUrl(), lat, lon, v);
	}

	public static JsonObject fetch(double lat, double lon) {
		if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) {
			throw new ToolException("Latitude must be -90..90 and longitude -180..180 (decimal degrees, e.g. 48.47, -81.33).");
		}
		String u = url(lat, lon);
		try {
			HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(30))
					.header("User-Agent", "openrocket-mcp").GET().build(), HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() != 200) {
				throw new ToolException("Weather service returned HTTP " + r.statusCode() + ": "
						+ r.body().substring(0, Math.min(300, r.body().length())));
			}
			return JsonParser.parseString(r.body()).getAsJsonObject();
		} catch (java.io.IOException e) {
			throw new ToolException("Could not reach the weather service (" + e.getMessage() + "). Without internet access, "
					+ "paste the forecast JSON from " + u.substring(0, Math.min(80, u.length())) + "... as forecastJson, or enter "
					+ "the winds by hand with wind_profile.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ToolException("Interrupted.");
		}
	}

	/**
	 * Picks one hour from an Open-Meteo hourly response. {@code time} is the site's local time "yyyy-MM-ddTHH:mm" (the
	 * nearest hour is used); null = the first hour at or after now (site time).
	 */
	public static Forecast parse(JsonObject json, String time) {
		if (json.has("error") && json.get("error").getAsBoolean()) {
			throw new ToolException("Weather service error: " + (json.has("reason") ? json.get("reason").getAsString() : json));
		}
		JsonObject h = json.getAsJsonObject("hourly");
		if (h == null || !h.has("time")) {
			throw new ToolException("Not an Open-Meteo hourly forecast (no hourly.time).");
		}
		JsonArray times = h.getAsJsonArray("time");
		int offset = json.has("utc_offset_seconds") ? json.get("utc_offset_seconds").getAsInt() : 0;
		String tz = json.has("timezone") ? json.get("timezone").getAsString() : "UTC";
		LocalDateTime want = time == null || time.isBlank()
				? LocalDateTime.now(java.time.ZoneOffset.ofTotalSeconds(offset))
				: LocalDateTime.parse(time.length() == 13 ? time + ":00" : time.length() == 10 ? time + "T12:00" : time);
		DateTimeFormatter f = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
		int best = -1;
		long bestDiff = Long.MAX_VALUE;
		for (int i = 0; i < times.size(); i++) {
			LocalDateTime t = LocalDateTime.parse(times.get(i).getAsString(), f);
			long d = Duration.between(want, t).toMinutes();
			if (time == null || time.isBlank() ? d >= -59 && d < bestDiff : Math.abs(d) < Math.abs(bestDiff)) {
				best = i;
				bestDiff = time == null || time.isBlank() ? d : Math.abs(d);
			}
		}
		if (best < 0 || (time != null && !time.isBlank() && bestDiff > 90)) {
			throw new ToolException("No forecast hour near " + (time == null ? "now" : time) + " (the forecast covers "
					+ times.get(0).getAsString() + " to " + times.get(times.size() - 1).getAsString() + ", site time " + tz + ").");
		}
		double elev = json.has("elevation") ? json.get("elevation").getAsDouble() : 0;
		double v10 = val(h, "wind_speed_10m", best), d10 = val(h, "wind_direction_10m", best);
		if (Double.isNaN(v10)) {
			throw new ToolException("The forecast has no 10 m wind for " + times.get(best).getAsString() + ".");
		}
		double gust = val(h, "wind_gusts_10m", best);
		List<Winds.Level> levels = new ArrayList<>();
		levels.add(new Winds.Level(0, v10, Math.toRadians(d10), sd(v10, gust)));
		levels.add(new Winds.Level(10, v10, Math.toRadians(d10), sd(v10, gust)));
		for (int z : NEAR_SURFACE) {
			double v = val(h, "wind_speed_" + z + "m", best), d = val(h, "wind_direction_" + z + "m", best);
			if (!Double.isNaN(v) && !Double.isNaN(d)) {
				levels.add(new Winds.Level(z, v, Math.toRadians(d), 0.1 * v));
			}
		}
		double highestNear = levels.get(levels.size() - 1).altitude();
		for (int p : PRESSURE_LEVELS) {
			double v = val(h, "wind_speed_" + p + "hPa", best), d = val(h, "wind_direction_" + p + "hPa", best);
			double z = val(h, "geopotential_height_" + p + "hPa", best);
			if (Double.isNaN(v) || Double.isNaN(d) || Double.isNaN(z)) {
				continue;
			}
			double agl = z - elev;
			if (agl <= highestNear + 20) {
				continue; // below ground or inside the near-surface levels
			}
			levels.add(new Winds.Level(agl, v, Math.toRadians(d), 0.1 * v));
		}
		levels.sort((a, b) -> Double.compare(a.altitude(), b.altitude()));
		double temp = val(h, "temperature_2m", best);
		double pres = val(h, "surface_pressure", best);
		return new Forecast(num(json, "latitude"), num(json, "longitude"), elev, times.get(best).getAsString(), tz, v10,
				Math.toRadians(d10), gust, Double.isNaN(temp) ? Double.NaN : temp + 273.15, Double.isNaN(pres) ? Double.NaN : pres * 100,
				levels);
	}

	/** Turbulence (standard deviation) from the gust factor: sd ~ (gust - mean) / 3, at least 10% of the mean. */
	static double sd(double mean, double gust) {
		return Double.isNaN(gust) ? 0.1 * mean : Math.max(0.1 * mean, (gust - mean) / 3);
	}

	private static double val(JsonObject hourly, String key, int i) {
		JsonElement a = hourly.get(key);
		if (a == null || !a.isJsonArray() || i >= a.getAsJsonArray().size() || a.getAsJsonArray().get(i).isJsonNull()) {
			return Double.NaN;
		}
		return a.getAsJsonArray().get(i).getAsDouble();
	}

	private static double num(JsonObject o, String k) {
		return o.has(k) ? o.get(k).getAsDouble() : Double.NaN;
	}

	/**
	 * Applies a forecast to a simulation: wind profile (AGL, up to {@code top}), launch site altitude, ground temperature
	 * and pressure (non-ISA atmosphere).
	 */
	public static void apply(SimulationOptions o, Forecast f, double top, double latitude, double longitude) {
		List<Winds.Level> ls = new ArrayList<>();
		for (Winds.Level l : f.levels()) {
			if (ls.isEmpty() || l.altitude() <= top || ls.get(ls.size() - 1).altitude() < top) {
				ls.add(l);
			}
		}
		o.setLaunchAltitude(f.elevation());
		o.setLaunchLatitude(latitude);
		o.setLaunchLongitude(longitude);
		if (!Double.isNaN(f.temperature()) && !Double.isNaN(f.pressure())) {
			o.setISAAtmosphere(false);
			o.setLaunchTemperature(f.temperature());
			// Surface pressure at the site; OpenRocket's launch pressure is at the launch altitude.
			o.setLaunchPressure(f.pressure());
		}
		Winds.setProfile(o, ls, true); // last: some OpenRocket option setters select the average wind model
	}
}
