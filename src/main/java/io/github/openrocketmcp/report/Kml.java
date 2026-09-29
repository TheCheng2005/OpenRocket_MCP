package io.github.openrocketmcp.report;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Landing zones as KML for Google Earth (desktop, web or phone): the pad, every simulated landing, and the 2-sigma
 * ellipse and mean landing point per stage, placed around the launch site's coordinates. Local offsets (east, north
 * in metres) are converted on a spherical earth, which is accurate to well under a metre over a few kilometres.
 */
public final class Kml {
	private Kml() {
	}

	static final double R = 6_371_000;
	/** Colours per stage, KML aabbggrr: blue, orange, green, purple. */
	static final String[] LINE = { "ffd6782a", "ff0f48d9", "ff3c9a2e", "ffa0479b" };
	static final String[] FILL = { "40d6782a", "400f48d9", "403c9a2e", "40a0479b" };

	/** {latitude, longitude} of a point {@code east}, {@code north} metres from (lat, lon). */
	public static double[] offset(double lat, double lon, double east, double north) {
		double dLat = Math.toDegrees(north / R);
		double dLon = Math.toDegrees(east / (R * Math.cos(Math.toRadians(lat))));
		return new double[] { lat + dLat, lon + dLon };
	}

	/**
	 * @param landings per stage: landing points {east, north} in metres
	 * @param ellipses per stage: {mean east, mean north, semi-major, semi-minor, major-axis angle from east (rad)}
	 */
	public static String landings(String title, double lat, double lon, Map<String, List<double[]>> landings,
			Map<String, double[]> ellipses, String description) {
		StringBuilder b = new StringBuilder();
		b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n");
		b.append("<name>").append(Xml.esc(title)).append("</name>\n");
		b.append("<description>").append(Xml.esc(description)).append("</description>\n");
		for (int k = 0; k < LINE.length; k++) {
			b.append(String.format(Locale.ROOT, "<Style id=\"s%d\"><IconStyle><color>%s</color><scale>0.5</scale><Icon><href>"
					+ "http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon></IconStyle><LabelStyle><scale>0</scale>"
					+ "</LabelStyle><LineStyle><color>%s</color><width>3</width></LineStyle><PolyStyle><color>%s</color></PolyStyle>"
					+ "</Style>%n", k, LINE[k], LINE[k], FILL[k]));
		}
		b.append("<Style id=\"pad\"><IconStyle><scale>1.2</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/"
				+ "triangle.png</href></Icon></IconStyle></Style>\n");
		b.append(String.format(Locale.ROOT, "<Placemark><name>Pad</name><styleUrl>#pad</styleUrl><Point><coordinates>%.7f,%.7f,0"
				+ "</coordinates></Point></Placemark>%n", lon, lat));
		int k = 0;
		for (Map.Entry<String, List<double[]>> e : landings.entrySet()) {
			String st = "#s" + (k % LINE.length);
			b.append("<Folder><name>").append(Xml.esc(e.getKey())).append(" (").append(e.getValue().size()).append(" landings)</name>\n");
			double[] el = ellipses.get(e.getKey());
			if (el != null) {
				double[] m = offset(lat, lon, el[0], el[1]);
				double dist = Math.hypot(el[0], el[1]);
				b.append(String.format(Locale.ROOT, "<Placemark><name>%s mean landing</name><description>%.0f m from the pad; 2-sigma "
						+ "ellipse %.0f x %.0f m</description><Point><coordinates>%.7f,%.7f,0</coordinates></Point></Placemark>%n",
						Xml.esc(e.getKey()), dist, 2 * el[2], 2 * el[3], m[1], m[0]));
				b.append("<Placemark><name>").append(Xml.esc(e.getKey())).append(" 2-sigma ellipse</name><styleUrl>").append(st)
						.append("</styleUrl><Polygon><tessellate>1</tessellate><outerBoundaryIs><LinearRing><coordinates>\n");
				for (int i = 0; i <= 72; i++) {
					double t = 2 * Math.PI * i / 72;
					double ex = el[2] * Math.cos(t), ny = el[3] * Math.sin(t);
					double east = el[0] + ex * Math.cos(el[4]) - ny * Math.sin(el[4]);
					double north = el[1] + ex * Math.sin(el[4]) + ny * Math.cos(el[4]);
					double[] p = offset(lat, lon, east, north);
					b.append(String.format(Locale.ROOT, "%.7f,%.7f,0 ", p[1], p[0]));
				}
				b.append("\n</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n");
			}
			b.append("<Folder><name>Simulated landings</name>\n");
			for (double[] pt : e.getValue()) {
				double[] p = offset(lat, lon, pt[0], pt[1]);
				b.append(String.format(Locale.ROOT, "<Placemark><styleUrl>%s</styleUrl><Point><coordinates>%.7f,%.7f,0</coordinates>"
						+ "</Point></Placemark>%n", st, p[1], p[0]));
			}
			b.append("</Folder>\n</Folder>\n");
			k++;
		}
		b.append("</Document>\n</kml>\n");
		return b.toString();
	}
}
