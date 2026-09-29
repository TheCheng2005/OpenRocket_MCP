package io.github.openrocketmcp.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.MonteCarlo;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.standards.Standards;

/** Landing zones for Google Earth. */
class KmlTest {
	@Test
	void offsetsMatchTheEarthsSize() {
		// One degree of latitude is 111.195 km on a 6371 km sphere; a degree of longitude shrinks with cos(latitude).
		double[] p = Kml.offset(45, -81, 0, 111_195);
		assertEquals(46, p[0], 1e-4);
		assertEquals(-81, p[1], 1e-12);
		p = Kml.offset(60, 10, 55_597.5, 0);
		assertEquals(11, p[1], 1e-4);
		assertEquals(60, p[0], 1e-12);
	}

	@Test
	void writesValidKmlWithPadEllipseAndLandings() throws Exception {
		Map<String, List<double[]>> land = new LinkedHashMap<>();
		land.put("Sustainer", List.of(new double[] { 100, 50 }, new double[] { -80, 20 }, new double[] { 30, -60 }));
		Map<String, double[]> el = Map.of("Sustainer", new double[] { 20, 5, 300, 150, Math.toRadians(30) });
		String kml = Kml.landings("Test & zones", 48.47, -81.33, land, el, "3 flights");
		Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(new ByteArrayInputStream(kml.getBytes(StandardCharsets.UTF_8)));
		assertEquals("kml", doc.getDocumentElement().getNodeName());
		assertEquals(1 + 2 + 3, doc.getElementsByTagName("Placemark").getLength(), "pad, mean point, ellipse, three landings");
		assertEquals(1, doc.getElementsByTagName("Polygon").getLength());
		// Every ellipse vertex lies on the ellipse: back to local metres, rotated into its axes.
		String ring = doc.getElementsByTagName("LinearRing").item(0).getTextContent().trim();
		for (String v : ring.split("\\s+")) {
			String[] c = v.split(",");
			double lon = Double.parseDouble(c[0]), lat = Double.parseDouble(c[1]);
			double north = Math.toRadians(lat - 48.47) * Kml.R - 5;
			double east = Math.toRadians(lon + 81.33) * Kml.R * Math.cos(Math.toRadians(48.47)) - 20;
			double t = Math.toRadians(30);
			double u = east * Math.cos(t) + north * Math.sin(t), w = -east * Math.sin(t) + north * Math.cos(t);
			assertEquals(1, u * u / (300 * 300) + w * w / (150 * 150), 0.01);
		}
		assertTrue(kml.contains("Test &amp; zones"));
	}

	@Test
	void monteCarloWritesTheKmlAndWarnsAboutADefaultSite(@TempDir Path dir) throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		var base = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		MonteCarlo.Settings s = new MonteCarlo.Settings(12, 0, 0, 0, 0, Double.NaN, 0, 0, Double.NaN, 7, 0, 0, 0, 0);
		Map<String, Object> out = MonteCarlo.run(base, d.doc, s, Standards.defaults(), null, dir.resolve("a.kml"), null);
		assertTrue(Files.readString(dir.resolve("a.kml")).contains("<Polygon>"));
		assertTrue(out.containsKey("kmlNote"), "no site in the standards: say the map is not on the team's field");
		out = MonteCarlo.run(base, d.doc, s, Standards.defaults(), null, dir.resolve("b.kml"), new double[] { 48.47, -81.33 });
		assertFalse(out.containsKey("kmlNote"));
		assertTrue(out.get("kml").toString().contains("48.47000, -81.33000"));
		assertTrue(Files.readString(dir.resolve("b.kml")).contains("-81.33"));
	}
}
