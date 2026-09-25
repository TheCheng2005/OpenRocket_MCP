package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Side-profile drawing of the active configuration from OpenRocket's own geometry (body radius profiles and fin
 * outlines, including pods), with the CG (launch and burnout) and CP marked, as SVG.
 */
public final class Drawing {
	private Drawing() {
	}

	private static final int W = 960, PAD = 40, TOP = 56, BOTTOM = 84;

	record Shape(List<double[]> pts, boolean fin) {
	}

	/** Outlines in rocket coordinates (x aft from the nose tip, y up), one closed polygon per body / fin instance. */
	static List<Shape> outlines(FlightConfiguration fc) {
		List<Shape> out = new ArrayList<>();
		for (RocketComponent c : fc.getActiveComponents()) {
			if (c instanceof SymmetricComponent sc && sc.getLength() > 0) {
				for (Coordinate a : unique(c.toAbsolute(Coordinate.NUL))) {
					int n = 40;
					List<double[]> top = new ArrayList<>(), bottom = new ArrayList<>();
					for (int i = 0; i <= n; i++) {
						double x = sc.getLength() * i / n;
						double r = sc.getRadius(x);
						top.add(new double[] { a.x + x, a.y + r });
						bottom.add(0, new double[] { a.x + x, a.y - r });
					}
					top.addAll(bottom);
					out.add(new Shape(top, false));
				}
			} else if (c instanceof FinSet f) {
				Coordinate[] pts = f.getFinPoints();
				double r = f.getBodyRadius();
				// Fin instances sit around the body; draw two (top and bottom) per body instance (pods included).
				double fx = f.toAbsolute(Coordinate.NUL)[0].x;
				for (Coordinate a : unique(f.getParent().toAbsolute(Coordinate.NUL))) {
					for (int side : new int[] { 1, -1 }) {
						List<double[]> poly = new ArrayList<>();
						for (Coordinate p : pts) {
							poly.add(new double[] { fx + p.x, a.y + side * (r + p.y) });
						}
						out.add(new Shape(poly, true));
					}
				}
			}
		}
		return out;
	}

	/** Instance positions projected on the drawing plane, without duplicates (fins at different angles share one). */
	private static List<Coordinate> unique(Coordinate[] cs) {
		Set<String> seen = new LinkedHashSet<>();
		List<Coordinate> out = new ArrayList<>();
		for (Coordinate c : cs) {
			if (seen.add(Math.round(c.x * 1e4) + ":" + Math.round(c.y * 1e4))) {
				out.add(c);
			}
		}
		return out;
	}

	public static String svg(FlightConfiguration fc, String title) {
		List<Shape> shapes = outlines(fc);
		double xmin = 0, xmax = 0, ymax = 0;
		for (Shape s : shapes) {
			for (double[] p : s.pts()) {
				xmin = Math.min(xmin, p[0]);
				xmax = Math.max(xmax, p[0]);
				ymax = Math.max(ymax, Math.abs(p[1]));
			}
		}
		if (xmax - xmin <= 0) {
			xmax = xmin + 1;
		}
		double scale = (W - 2 * PAD) / (xmax - xmin);
		int bodyH = (int) Math.ceil(2 * ymax * scale);
		int h = TOP + Math.max(bodyH, 20) + BOTTOM;
		double cy = TOP + Math.max(bodyH, 20) / 2.0;
		final double x0 = xmin;
		java.util.function.DoubleUnaryOperator sx = x -> PAD + (x - x0) * scale;
		java.util.function.DoubleUnaryOperator sy = y -> cy - y * scale;

		Analysis.Stability st = Analysis.stability(fc, 0.3);
		StringBuilder s = new StringBuilder();
		s.append(String.format(Locale.ROOT,
				"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" role=\"img\" aria-label=\"%s\">%n",
				W, h, W, h, esc(title)));
		s.append("<style>.bg{fill:#fcfcfb}.body{fill:#e9eef6;stroke:#2a4a78;stroke-width:1.2}.fin{fill:#c9d7ec;stroke:#2a4a78;stroke-width:1.2}")
				.append(".axis{stroke:#b8b7ae;stroke-width:1;stroke-dasharray:4 4}.t1{fill:#1a1a19;font:600 15px system-ui,sans-serif}")
				.append(".t2{fill:#5f5e57;font:12px system-ui,sans-serif}.cg{fill:#1a1a19}.cp{fill:#c0392b}.cgb{fill:#7f8c8d}")
				.append("@media (prefers-color-scheme: dark){.bg{fill:#1a1a19}.body{fill:#24344d;stroke:#9db8e0}.fin{fill:#34496a;stroke:#9db8e0}")
				.append(".t1{fill:#fff}.t2{fill:#c3c2b7}.cg{fill:#fff}.cgb{fill:#aab}.axis{stroke:#5f5e57}}</style>\n");
		s.append(String.format(Locale.ROOT, "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>%n", W, h));
		s.append(String.format(Locale.ROOT, "<text class=\"t1\" x=\"%d\" y=\"26\">%s</text>%n", PAD, esc(title)));
		s.append(String.format(Locale.ROOT, "<line class=\"axis\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>%n", PAD - 10, W - PAD + 10, cy, cy));
		for (Shape sh : shapes) { // bodies first, fins on top
			if (!sh.fin()) {
				s.append(poly(sh, "body", sx, sy));
			}
		}
		for (Shape sh : shapes) {
			if (sh.fin()) {
				s.append(poly(sh, "fin", sx, sy));
			}
		}
		marker(s, "cg", sx.applyAsDouble(st.cgLaunchX()), cy, "CG " + Units.fmt(st.cgLaunchX(), Dim.LENGTH), h - BOTTOM + 22);
		marker(s, "cgb", sx.applyAsDouble(st.cgBurnoutX()), cy, null, 0);
		marker(s, "cp", sx.applyAsDouble(st.cpX()), cy, "CP " + Units.fmt(st.cpX(), Dim.LENGTH), h - BOTTOM + 40);
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%d\">Length %s, max diameter %s, launch mass %s.</text>%n"
				+ "<text class=\"t2\" x=\"%d\" y=\"%d\">Stability %s cal at launch, %s cal at burnout (Mach 0.3). Grey marker: burnout CG.</text>%n",
				PAD, h - 24,
				esc(Units.fmt(st.length(), Dim.LENGTH)), esc(Units.fmt(st.referenceDiameter(), Dim.LENGTH)),
				esc(Units.fmt(st.launchMass(), Dim.MASS)), PAD, h - 8, Units.num(st.marginCalibers()), Units.num(st.burnoutMarginCalibers())));
		s.append("</svg>\n");
		return s.toString();
	}

	private static String poly(Shape sh, String cls, java.util.function.DoubleUnaryOperator sx, java.util.function.DoubleUnaryOperator sy) {
		StringBuilder p = new StringBuilder();
		for (double[] pt : sh.pts()) {
			p.append(String.format(Locale.ROOT, "%.1f,%.1f ", sx.applyAsDouble(pt[0]), sy.applyAsDouble(pt[1])));
		}
		return "<polygon class=\"" + cls + "\" points=\"" + p.toString().trim() + "\"/>\n";
	}

	/** CG: black/white quartered circle; CP: red circle with a dot. */
	private static void marker(StringBuilder s, String cls, double x, double cy, String label, double labelY) {
		if (Double.isNaN(x)) {
			return;
		}
		if (cls.startsWith("cg")) {
			s.append(String.format(Locale.ROOT, "<circle class=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"7\"/>"
					+ "<path d=\"M%.1f %.1f h7 a7 7 0 0 1 -7 7 z M%.1f %.1f h-7 a7 7 0 0 1 7 -7 z\" fill=\"#fcfcfb\"/>%n",
					cls, x, cy, x, cy, x, cy));
		} else {
			s.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"7\" fill=\"none\" stroke=\"#c0392b\" stroke-width=\"2\"/>"
					+ "<circle class=\"cp\" cx=\"%.1f\" cy=\"%.1f\" r=\"2.5\"/>%n", x, cy, x, cy));
		}
		if (label != null) {
			s.append(String.format(Locale.ROOT, "<line class=\"axis\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%.1f\"/>%n", x, x, cy + 9, labelY - 12));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>%n", x, labelY,
					esc(label)));
		}
	}

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
