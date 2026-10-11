package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Descent rate against canopy diameter: one curve per drag coefficient (up to three, direct-labelled), the rule band
 * or limit and the target as reference lines, catalog parachutes as hollow dots (each at its own Cd), the size each
 * curve needs for the target, and the parachute the design has now. Values arrive in display units. Colours are the
 * first three slots of the validated categorical palette, light and dark; hover titles on every mark.
 */
public final class ChuteChart {
	private ChuteChart() {
	}

	/** One Cd: rates (display units) at the chart's diameters, and the diameter for the target (NaN = none). */
	public record Curve(String label, double[] rate, double sizeForTarget) {
	}

	/** A point with a hover title: a catalog parachute or the current one. */
	public record Dot(double diameter, double rate, String title) {
	}

	/** Horizontal line (or band when {@code hi} is set) with its label. */
	public record Ref(double lo, double hi, String label) {
	}

	private static final int W = 760, H = 420, L = 64, R = 132, T = 64, B = 52;
	private static final String[] SLOTS = { "c1", "c2", "c3" };

	public static String svg(String title, String xUnit, String yUnit, double[] diameters, List<Curve> curves,
			double target, String targetLabel, List<Ref> limits, List<Dot> catalog, Dot current) {
		double xmin = diameters[0], xmax = diameters[diameters.length - 1];
		// The curves run to infinity at small sizes: show up to well past the target and the limits.
		double ycap = 0;
		for (Ref r : limits) {
			ycap = Math.max(ycap, Double.isNaN(r.hi()) ? r.lo() : r.hi());
		}
		ycap = Math.max(ycap * 1.35, Double.isNaN(target) ? 0 : target * 2.2);
		if (current != null) {
			ycap = Math.max(ycap, current.rate() * 1.2);
		}
		if (ycap <= 0) {
			for (Curve c : curves) {
				for (double v : c.rate()) {
					ycap = Math.max(ycap, Double.isFinite(v) ? Math.min(v, c.rate()[c.rate().length - 1] * 4) : 0);
				}
			}
		}
		double[] xt = ticks(xmin, xmax), yt = ticks(0, ycap);
		final double x0 = xt[0], x1 = xt[xt.length - 1], y1 = yt[yt.length - 1];
		DoubleUnaryOperator sx = v -> L + (v - x0) / (x1 - x0) * (W - L - R);
		DoubleUnaryOperator sy = v -> H - B - v / y1 * (H - T - B);

		StringBuilder s = new StringBuilder();
		s.append(String.format(Locale.ROOT,
				"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" role=\"img\" aria-label=\"%s\">%n",
				W, H, W, H, Xml.esc(title)));
		s.append("<style>\n")
				.append(".bg{fill:#fcfcfb}.grid{stroke:#e1e0d9;stroke-width:1}.axis{stroke:#c3c2b7;stroke-width:1}")
				.append(".t1{fill:#0b0b0b;font:600 15px system-ui,sans-serif}.t2{fill:#52514e;font:12px system-ui,sans-serif}")
				.append(".t3{fill:#0b0b0b;font:600 12px system-ui,sans-serif}.mut{fill:#898781;font:11px system-ui,sans-serif}")
				.append(".c1{stroke:#2a78d6}.c2{stroke:#eb6834}.c3{stroke:#1baf7a}.f1{fill:#2a78d6}.f2{fill:#eb6834}.f3{fill:#1baf7a}")
				.append(".curve{fill:none;stroke-width:2;stroke-linejoin:round;stroke-linecap:round}")
				.append(".band{fill:#0b0b0b;fill-opacity:.05}.lim{stroke:#52514e;stroke-width:1.5;stroke-dasharray:6 4}")
				.append(".tgt{stroke:#0b0b0b;stroke-width:1.5}.cat{fill:none;stroke:#898781;stroke-width:1.2;stroke-opacity:.6}")
				.append(".drop{stroke-width:1;stroke-dasharray:2 3}.halo{stroke:#fcfcfb;stroke-width:4;stroke-linejoin:round}")
				.append(".ring{stroke:#fcfcfb;stroke-width:2}.now{fill:#0b0b0b}\n")
				.append("@media (prefers-color-scheme: dark){.bg{fill:#1a1a19}.grid{stroke:#2c2c2a}.axis{stroke:#383835}")
				.append(".t1,.t3{fill:#f0efec}.t2{fill:#c3c2b7}.c1{stroke:#3987e5}.c2{stroke:#d95926}.c3{stroke:#199e70}")
				.append(".f1{fill:#3987e5}.f2{fill:#d95926}.f3{fill:#199e70}.band{fill:#f0efec}.lim{stroke:#c3c2b7}")
				.append(".tgt{stroke:#f0efec}.ring,.halo{stroke:#1a1a19}.now{fill:#f0efec}}\n")
				.append("</style>\n");
		s.append(String.format(Locale.ROOT, "<rect class=\"bg\" width=\"%d\" height=\"%d\"/>%n", W, H));
		s.append(String.format(Locale.ROOT, "<text class=\"t1\" x=\"%d\" y=\"24\">%s</text>%n", L, Xml.esc(title)));

		// Legend row: curves (when more than one), catalog dots, current parachute.
		double lx = L;
		int ly = 44;
		if (curves.size() > 1) {
			for (int k = 0; k < curves.size() && k < SLOTS.length; k++) {
				s.append(String.format(Locale.ROOT, "<line class=\"curve %s\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%d\" y2=\"%d\"/>"
						+ "<text class=\"t2\" x=\"%.1f\" y=\"%d\">%s</text>%n", SLOTS[k], lx, lx + 18, ly, ly, lx + 24, ly + 4,
						Xml.esc(curves.get(k).label())));
				lx += 34 + 7 * curves.get(k).label().length();
			}
		}
		if (!catalog.isEmpty()) {
			s.append(String.format(Locale.ROOT, "<circle class=\"cat\" cx=\"%.1f\" cy=\"%d\" r=\"4\"/><text class=\"t2\" x=\"%.1f\" y=\"%d\">"
					+ "catalog parachute (its own Cd)</text>%n", lx + 5, ly, lx + 14, ly + 4));
			lx += 210;
		}
		if (current != null) {
			s.append(String.format(Locale.ROOT, "<circle class=\"now ring\" cx=\"%.1f\" cy=\"%d\" r=\"5\"/><text class=\"t2\" x=\"%.1f\" "
					+ "y=\"%d\">now</text>%n", lx + 5, ly, lx + 14, ly + 4));
		}

		// Grid and axes.
		for (double v : yt) {
			double py = sy.applyAsDouble(v);
			s.append(String.format(Locale.ROOT, "<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>%n", L, W - R, py, py));
			s.append(String.format(Locale.ROOT, "<text class=\"mut\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>%n", L - 8, py + 4, fmt(v)));
		}
		for (double v : xt) {
			s.append(String.format(Locale.ROOT, "<text class=\"mut\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>%n",
					sx.applyAsDouble(v), H - B + 18, fmt(v)));
		}
		s.append(String.format(Locale.ROOT, "<line class=\"axis\" x1=\"%d\" x2=\"%d\" y1=\"%d\" y2=\"%d\"/>%n", L, W - R, H - B, H - B));
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%d\" text-anchor=\"middle\">Canopy diameter (%s)</text>%n",
				(L + W - R) / 2, H - 12, Xml.esc(xUnit)));
		s.append(String.format(Locale.ROOT, "<text class=\"t2\" transform=\"translate(16 %d) rotate(-90)\" text-anchor=\"middle\">"
				+ "Steady descent rate (%s)</text>%n", (T + H - B) / 2, Xml.esc(yUnit)));

		// Rule band / limit and target, labelled at the right.
		for (Ref r : limits) {
			if (!Double.isNaN(r.hi())) {
				double top = sy.applyAsDouble(Math.min(r.hi(), y1)), bot = sy.applyAsDouble(r.lo());
				s.append(String.format(Locale.ROOT, "<rect class=\"band\" x=\"%d\" y=\"%.1f\" width=\"%d\" height=\"%.1f\"><title>%s</title></rect>%n",
						L, top, W - R - L, bot - top, Xml.esc(r.label())));
				s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%.1f\">%s</text>%n", W - R + 8, top + 12,
						Xml.esc(r.label())));
			} else if (r.lo() <= y1) {
				double py = sy.applyAsDouble(r.lo());
				s.append(String.format(Locale.ROOT, "<line class=\"lim\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"><title>%s</title></line>%n",
						L, W - R, py, py, Xml.esc(r.label())));
				s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%.1f\">%s</text>%n", W - R + 8, py + 4, Xml.esc(r.label())));
			}
		}
		if (!Double.isNaN(target) && target <= y1) {
			double py = sy.applyAsDouble(target);
			s.append(String.format(Locale.ROOT, "<line class=\"tgt\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"><title>%s</title></line>%n",
					L, W - R, py, py, Xml.esc(targetLabel)));
			s.append(String.format(Locale.ROOT, "<text class=\"t3\" x=\"%d\" y=\"%.1f\">%s</text>%n", W - R + 8, py + 4, Xml.esc(targetLabel)));
		}

		// Catalog parachutes under the curves.
		for (Dot d : catalog) {
			if (d.diameter() < x0 || d.diameter() > x1 || d.rate() > y1) {
				continue;
			}
			s.append(String.format(Locale.ROOT, "<circle class=\"cat\" cx=\"%.1f\" cy=\"%.1f\" r=\"3.5\"><title>%s</title></circle>%n",
					sx.applyAsDouble(d.diameter()), sy.applyAsDouble(d.rate()), Xml.esc(d.title())));
		}

		// Curves, each labelled at its right end, with the size it needs for the target.
		List<double[]> labelYs = new ArrayList<>();
		List<Double> sizeXs = new ArrayList<>();
		for (int k = 0; k < curves.size() && k < SLOTS.length; k++) {
			Curve c = curves.get(k);
			StringBuilder path = new StringBuilder();
			boolean pen = false;
			for (int i = 0; i < diameters.length; i++) {
				double v = c.rate()[i];
				if (!Double.isFinite(v) || v > y1) {
					pen = false;
					continue;
				}
				path.append(pen ? " L" : " M").append(String.format(Locale.ROOT, "%.1f %.1f", sx.applyAsDouble(diameters[i]),
						sy.applyAsDouble(v)));
				pen = true;
			}
			s.append(String.format(Locale.ROOT, "<path class=\"curve %s\" d=\"%s\"><title>%s</title></path>%n", SLOTS[k],
					path.toString().trim(), Xml.esc(c.label())));
			double endY = sy.applyAsDouble(Math.min(c.rate()[c.rate().length - 1], y1));
			for (double[] used : labelYs) {
				if (Math.abs(used[0] - endY) < 14) {
					endY = used[0] + (endY >= used[0] ? 14 : -14);
				}
			}
			labelYs.add(new double[] { endY });
			s.append(String.format(Locale.ROOT, "<text class=\"t2\" x=\"%d\" y=\"%.1f\">%s</text>%n", W - R + 8, endY + 4,
					Xml.esc(c.label())));
			if (!Double.isNaN(c.sizeForTarget()) && c.sizeForTarget() >= x0 && c.sizeForTarget() <= x1 && !Double.isNaN(target)) {
				double px = sx.applyAsDouble(c.sizeForTarget()), py = sy.applyAsDouble(target);
				// A drop line to the axis, the size written in the empty band just above it (rates near zero never occur).
				double ly2 = H - B - 8;
				for (double used : sizeXs) {
					if (Math.abs(used - px) < 56) {
						ly2 -= 15;
					}
				}
				sizeXs.add(px);
				s.append(String.format(Locale.ROOT, "<line class=\"drop %s\" x1=\"%.1f\" x2=\"%.1f\" y1=\"%.1f\" y2=\"%d\"/>%n", SLOTS[k], px, px,
						py, H - B));
				s.append(String.format(Locale.ROOT, "<circle class=\"%s ring\" cx=\"%.1f\" cy=\"%.1f\" r=\"5\"><title>%s: %s %s for the target"
						+ "</title></circle>%n", SLOTS[k].replace('c', 'f'), px, py, Xml.esc(c.label()), fmt(c.sizeForTarget()), Xml.esc(xUnit)));
				String size = fmt(c.sizeForTarget()) + " " + xUnit;
				s.append(String.format(Locale.ROOT, "<text class=\"t3 halo\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>"
						+ "<text class=\"t3\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\">%s</text>%n", px, ly2, size, px, ly2, size));
			}
		}

		// The parachute the design has now.
		if (current != null && current.diameter() >= x0 && current.diameter() <= x1 && current.rate() <= y1) {
			double px = sx.applyAsDouble(current.diameter()), py = sy.applyAsDouble(current.rate());
			s.append(String.format(Locale.ROOT, "<circle class=\"now ring\" cx=\"%.1f\" cy=\"%.1f\" r=\"6\"><title>%s</title></circle>%n", px,
					py, Xml.esc(current.title())));
			s.append(String.format(Locale.ROOT, "<text class=\"t3 halo\" x=\"%.1f\" y=\"%.1f\">now</text><text class=\"t3\" x=\"%.1f\" "
					+ "y=\"%.1f\">now</text>%n", px + 9, py + 17, px + 9, py + 17));
		}
		s.append("</svg>\n");
		return s.toString();
	}

	public static double[] ticks(double lo, double hi) {
		double span = hi - lo <= 0 ? 1 : hi - lo;
		double raw = span / 6, mag = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / mag;
		double step = (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * mag;
		double start = Math.floor(lo / step) * step, end = Math.ceil(hi / step) * step;
		int n = (int) Math.round((end - start) / step) + 1;
		double[] t = new double[n];
		for (int i = 0; i < n; i++) {
			t[i] = start + i * step;
		}
		return t;
	}

	public static String fmt(double v) {
		double a = Math.abs(v);
		return a >= 100 || v == Math.rint(v) ? String.format(Locale.ROOT, "%.0f", v) : a >= 10 ? String.format(Locale.ROOT, "%.1f", v)
				: String.format(Locale.ROOT, "%.2f", v);
	}
}
