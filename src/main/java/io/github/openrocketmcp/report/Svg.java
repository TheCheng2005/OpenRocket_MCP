package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Minimal static SVG line chart for design-review documents: one series, one y axis, recessive grid, an optional
 * labelled reference line (e.g. a rule minimum) and thin event markers. Light and dark themes via
 * prefers-color-scheme. The data table is the CSV written next to it.
 */
public final class Svg {
	private Svg() {
	}

	public record Marker(double x, String label) {
	}

	private static final int W = 720, H = 360, L = 64, R = 24, T = 48, B = 52;

	public static String line(String title, String xLabel, String yLabel, double[] x, double[] y, double refY,
			String refLabel, List<Marker> markers) {
		double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE, ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
		for (int i = 0; i < x.length; i++) {
			if (Double.isNaN(y[i]) || Double.isInfinite(y[i])) {
				continue;
			}
			xmin = Math.min(xmin, x[i]);
			xmax = Math.max(xmax, x[i]);
			ymin = Math.min(ymin, y[i]);
			ymax = Math.max(ymax, y[i]);
		}
		if (xmin > xmax) {
			xmin = 0;
			xmax = 1;
			ymin = 0;
			ymax = 1;
		}
		if (!Double.isNaN(refY)) {
			ymin = Math.min(ymin, refY);
			ymax = Math.max(ymax, refY);
		}
		double[] yt = ticks(ymin, ymax);
		double[] xt = ticks(xmin, xmax);
		final double x0 = xt[0], x1 = xt[xt.length - 1], y0 = yt[0], y1 = yt[yt.length - 1];
		DoubleUnaryOperator sx = v -> L + (v - x0) / (x1 - x0) * (W - L - R);
		DoubleUnaryOperator sy = v -> H - B - (v - y0) / (y1 - y0) * (H - T - B);

		StringBuilder s = new StringBuilder();
		s.append(String.format(Locale.ROOT,
				"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" role=\"img\" aria-label=\"%s\">%n",
				W, H, W, H, esc(title)));
		s.append("<style>\n")
				.append(".bg{fill:#fcfcfb}.grid{stroke:#e4e3de;stroke-width:1}.axis{stroke:#b8b7ae;stroke-width:1}")
				.append(".t1{fill:#1a1a19;font:600 15px system-ui,sans-serif}.t2{fill:#5f5e57;font:12px system-ui,sans-serif}")
				.append(".series{fill:none;stroke:#2a78d6;stroke-width:2;stroke-linejoin:round;stroke-linecap:round}")
				.append(".ref{stroke:#5f5e57;stroke-width:1.5;stroke-dasharray:6 4}.mark{stroke:#b8b7ae;stroke-width:1}\n")
				.append("@media (prefers-color-scheme: dark){.bg{fill:#1a1a19}.grid{stroke:#34332f}.axis{stroke:#5f5e57}")
				.append(".t1{fill:#ffffff}.t2{fill:#c3c2b7}.series{stroke:#3987e5}.ref{stroke:#c3c2b7}.mark{stroke:#5f5e57}}\n")
				.append("</style>\n");
		s.append(String.format(Locale.ROOT, "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>%n", W, H));
		s.append(String.format(Locale.ROOT, "<text class=\"t1\" x=\"%d\" y=\"26\">%s</text>%n", L, esc(title)));
		for (double v : yt) {
			double py = sy.applyAsDouble(v);
			s.append(String.format(Locale.ROOT, "<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>%n", L, W - R, py, py));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>%n", L - 8, py + 4, fmt(v)));
		}
		for (double v : xt) {
			double px = sx.applyAsDouble(v);
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>%n", px, H - B + 18, fmt(v)));
		}
		s.append(String.format(Locale.ROOT, "<line class=\"axis\" x1=\"%d\" x2=\"%d\" y1=\"%d\" y2=\"%d\"/>%n", L, W - R, H - B, H - B));
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%d\" text-anchor=\"middle\">%s</text>%n",
				(L + W - R) / 2, H - 12, esc(xLabel)));
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" transform=\"translate(16 %d) rotate(-90)\" text-anchor=\"middle\">%s</text>%n",
				(T + H - B) / 2, esc(yLabel)));
		if (markers != null) {
			appendMarkers(s, markers, sx, x0, x1);
		}
		if (!Double.isNaN(refY)) {
			double py = sy.applyAsDouble(refY);
			s.append(String.format(Locale.ROOT, "<line class=\"ref\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>%n", L, W - R, py, py));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>%n", W - R, py - 6, esc(refLabel)));
		}
		StringBuilder path = new StringBuilder();
		boolean pen = false;
		for (int i = 0; i < x.length; i++) {
			if (Double.isNaN(y[i]) || Double.isInfinite(y[i]) || x[i] < x0 || x[i] > x1) {
				pen = false;
				continue;
			}
			path.append(pen ? " L" : " M").append(String.format(Locale.ROOT, "%.1f %.1f", sx.applyAsDouble(x[i]), sy.applyAsDouble(y[i])));
			pen = true;
		}
		s.append("<path class=\"series\" d=\"").append(path.toString().trim()).append("\"/>\n");
		s.append("</svg>\n");
		return s.toString();
	}

	/** Event markers: events at the same time share one label; labels that would collide drop to the next row. */
	private static void appendMarkers(StringBuilder s, List<Marker> markers, DoubleUnaryOperator sx, double x0, double x1) {
		List<Marker> merged = new ArrayList<>();
		for (Marker m : markers) {
			if (m.x() < x0 || m.x() > x1) {
				continue;
			}
			Marker last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			if (last != null && Math.abs(last.x() - m.x()) < 1e-3) {
				if (!last.label().contains(m.label())) {
					merged.set(merged.size() - 1, new Marker(last.x(), last.label() + " + " + m.label()));
				}
			} else {
				merged.add(m);
			}
		}
		double[] rowEnd = { -1e9, -1e9, -1e9 };
		for (Marker m : merged) {
			double px = sx.applyAsDouble(m.x());
			int row = 0;
			while (row < rowEnd.length - 1 && px < rowEnd[row] + 6) {
				row++;
			}
			rowEnd[row] = px + 4 + m.label().length() * 6.5;
			s.append(String.format(Locale.ROOT, "<line class=\"mark\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%d\" y2=\"%d\"/>%n", px, px, T, H - B));
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%.1f\" y=\"%d\" text-anchor=\"start\">%s</text>%n",
					px + 4, T + 12 + 14 * row, esc(m.label())));
		}
	}

	/** "Nice" axis ticks (1, 2, 5 x 10^n steps) covering [lo, hi]. */
	static double[] ticks(double lo, double hi) {
		if (hi - lo < 1e-9) {
			hi = lo + 1;
		}
		double raw = (hi - lo) / 5;
		double mag = Math.pow(10, Math.floor(Math.log10(raw)));
		double step = raw / mag >= 5 ? 5 * mag : raw / mag >= 2 ? 2 * mag : mag;
		double start = Math.floor(lo / step) * step;
		double end = Math.ceil(hi / step) * step;
		int n = (int) Math.round((end - start) / step) + 1;
		double[] t = new double[n];
		for (int i = 0; i < n; i++) {
			t[i] = start + i * step;
		}
		return t;
	}

	private static String fmt(double v) {
		if (Math.abs(v - Math.rint(v)) < 1e-9) {
			return String.valueOf((long) Math.rint(v));
		}
		return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
