package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;

class WeatherTest {
	static String sample() throws Exception {
		try (InputStream in = WeatherTest.class.getResourceAsStream("/open-meteo-sample.json")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	static JsonObject json() throws Exception {
		return JsonParser.parseString(sample()).getAsJsonObject();
	}

	@Test
	void parsesTheRequestedHourAndLevels() throws Exception {
		Weather.Forecast f = Weather.parse(json(), "2027-08-21T10:00");
		assertEquals("2027-08-21T10:00", f.time());
		assertEquals(3 + 0.25 * 10, f.speed(), 1e-9);
		assertEquals(Math.toRadians(250), f.direction(), 1e-9);
		assertEquals(978.0 * 100, f.pressure(), 1e-6);
		assertEquals(18 + 5 + 273.15, f.temperature(), 1e-9);
		assertEquals(300, f.elevation(), 0);
		List<Winds.Level> l = f.levels();
		for (int i = 1; i < l.size(); i++) {
			assertTrue(l.get(i).altitude() > l.get(i - 1).altitude(), "sorted, no duplicates");
		}
		assertEquals(0, l.get(0).altitude(), 0);
		assertTrue(l.stream().noneMatch(x -> x.altitude() < 0), "levels below ground (1000 hPa at 110 m MSL) dropped");
		assertTrue(l.stream().noneMatch(x -> Math.abs(x.altitude() - 30) < 1), "975 hPa inside the near-surface levels dropped");
		assertTrue(l.stream().anyMatch(x -> Math.abs(x.altitude() - (1460 - 300)) < 1e-6), "850 hPa at 1160 m AGL");
		assertTrue(l.stream().noneMatch(x -> Math.abs(x.altitude() - (13600 - 300)) < 1e-6), "null value at 150 hPa skipped");
		// Turbulence from the gust factor: (gust - mean) / 3
		assertEquals((6 + 4 - 5.5) / 3, l.get(0).sd(), 1e-9);
	}

	@Test
	void nearestHourAndErrors() throws Exception {
		assertEquals("2027-08-21T11:00", Weather.parse(json(), "2027-08-21T10:40").time());
		assertEquals("2027-08-21T12:00", Weather.parse(json(), "2027-08-21").time(), "a date alone means noon");
		ToolException e = assertThrows(ToolException.class, () -> Weather.parse(json(), "2027-09-30T10:00"));
		assertTrue(e.getMessage().contains("covers"), e.getMessage());
		JsonObject err = JsonParser.parseString("{\"error\":true,\"reason\":\"Latitude must be in range\"}").getAsJsonObject();
		assertTrue(assertThrows(ToolException.class, () -> Weather.parse(err, null)).getMessage().contains("Latitude"));
		assertThrows(ToolException.class, () -> Weather.fetch(95, 0));
	}

	@Test
	void requestAsksForEveryLevel() {
		String u = Weather.url(48.47, -81.33);
		for (int p : Weather.PRESSURE_LEVELS) {
			assertTrue(u.contains("wind_speed_" + p + "hPa") && u.contains("geopotential_height_" + p + "hPa"), "level " + p);
		}
		assertTrue(u.contains("wind_speed_unit=ms") && u.contains("timezone=auto") && u.contains("latitude=48.47000"));
	}

	@Test
	void fetchesOverHttpAndAppliesToTheSimulation(@TempDir Path tmp) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String[] query = new String[1];
		byte[] body = sample().getBytes(StandardCharsets.UTF_8);
		server.createContext("/v1/forecast", ex -> {
			query[0] = ex.getRequestURI().getQuery();
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.start();
		String old = System.getProperty("openrocketmcp.weatherUrl");
		System.setProperty("openrocketmcp.weatherUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/forecast");
		try {
			JsonObject j = Weather.fetch(48.47, -81.33);
			assertTrue(query[0].contains("latitude=48.47"), query[0]);
			Weather.Forecast f = Weather.parse(j, "2027-08-21T14:00");

			Designs.Design d = new Designs().openExample("Dual parachute");
			Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
			Weather.apply(sim.getOptions(), f, 3000, 48.47, -81.33);
			assertTrue(Winds.isMultiLevel(sim.getOptions()));
			assertEquals(300, sim.getOptions().getLaunchAltitude(), 1e-9);
			assertFalse(sim.getOptions().isISAAtmosphere());
			assertEquals(f.temperature(), sim.getOptions().getLaunchTemperature(), 1e-9);
			List<Winds.Level> applied = Winds.levels(sim.getOptions());
			assertTrue(applied.get(applied.size() - 1).altitude() >= 3000 && applied.size() < f.levels().size(), "cut at the top");
			Sims.run(sim);
			assertTrue(Winds.isMultiLevel(sim.getOptions()), "profile survives the run");

			// Flight card from the forecast conditions
			Path card = tmp.resolve("card.md");
			Map<String, Object> out = io.github.openrocketmcp.report.FlightCard.write(d, sim, Standards.defaults(), card, "test site");
			String md = Files.readString(card);
			for (String h : new String[] { "# Flight card", "## Vehicle", "## Motors", "optimumDelay", "## Recovery settings",
					"### Sections", "## Drift vs ground wind", "## Rule check", "## Sign-off" }) {
				assertTrue(md.contains(h), "card has " + h);
			}
			assertTrue(out.get("apogee") != null);
		} finally {
			server.stop(0);
			if (old == null) {
				System.clearProperty("openrocketmcp.weatherUrl");
			} else {
				System.setProperty("openrocketmcp.weatherUrl", old);
			}
		}
	}

	@Test
	void unreachableServiceSuggestsManualEntry() {
		String old = System.getProperty("openrocketmcp.weatherUrl");
		System.setProperty("openrocketmcp.weatherUrl", "http://127.0.0.1:9/v1/forecast");
		try {
			ToolException e = assertThrows(ToolException.class, () -> Weather.fetch(48, -81));
			assertTrue(e.getMessage().contains("forecastJson") && e.getMessage().contains("wind_profile"), e.getMessage());
		} finally {
			if (old == null) {
				System.clearProperty("openrocketmcp.weatherUrl");
			} else {
				System.setProperty("openrocketmcp.weatherUrl", old);
			}
		}
	}
}
