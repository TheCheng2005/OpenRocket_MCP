package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Whole-planform fin optimization: root chord, taper, span and sweep (and optionally thickness from the stock the team
 * can buy) searched together with the simulated stability, fin flutter and manufacturing limits as constraints.
 *
 * <p>The search runs in a shape space that cannot produce unbuildable fins: tip chord = taper x root chord (never
 * below the minimum tip chord), sweep = sweepFraction x (root - tip), so a fraction up to 1 keeps the tip trailing edge
 * ahead of the root trailing edge (no aft overhang to break on landing), and the leading-edge sweep angle is capped.
 */
public final class FinDesign {
	private FinDesign() {
	}

	/** Search bounds and manufacturing limits (SI). */
	public record Limits(double rootMin, double rootMax, double spanMin, double spanMax, double taperMin, double taperMax,
			double minTip, double maxSweepFraction, double maxSweepAngle) {
	}

	/** A trapezoidal planform. */
	public record Planform(double root, double tip, double span, double sweep, double thickness) {
		public double area() {
			return 0.5 * (root + tip) * span;
		}

		public double sweepAngle() {
			return Math.atan2(sweep, span);
		}

		/** Aspect ratio of one fin panel, span^2 / area. */
		public double aspectRatio() {
			return span * span / area();
		}

		/** How far the tip trailing edge sits aft of the root trailing edge (positive = overhang). */
		public double aftOverhang() {
			return sweep + tip - root;
		}
	}

	/** Search variables: root chord, taper ratio, span, sweep fraction. */
	public static Planform shape(double[] x, Limits lim, double thickness) {
		double root = x[0], span = x[2];
		double tip = Math.max(x[1] * root, Math.min(lim.minTip(), root));
		double sweep = x[3] * Math.max(0, root - tip);
		if (x[3] > 1) { // overhang allowed: the fraction beyond 1 moves the tip aft of the root trailing edge
			sweep = (root - tip) + (x[3] - 1) * root;
		}
		if (!Double.isNaN(lim.maxSweepAngle())) {
			sweep = Math.min(sweep, span * Math.tan(lim.maxSweepAngle()));
		}
		return new Planform(root, tip, span, Math.max(0, sweep), thickness);
	}

	public static Planform of(TrapezoidFinSet f) {
		return new Planform(f.getRootChord(), f.getTipChord(), f.getHeight(), f.getSweep(), f.getThickness());
	}

	public static void applyTo(TrapezoidFinSet f, Planform p) {
		f.setFinShape(p.root(), p.tip(), p.sweep(), p.span(), p.thickness());
	}

	/** Search variables of the current design (inverse of {@link #shape}, clamped to the limits). */
	static double[] variables(Planform p, Limits lim) {
		double taper = p.root() > 0 ? p.tip() / p.root() : 0.5;
		double fr = p.root() > p.tip() ? p.sweep() / (p.root() - p.tip()) : 0.5;
		return new double[] { clamp(p.root(), lim.rootMin(), lim.rootMax()), clamp(taper, lim.taperMin(), lim.taperMax()),
				clamp(p.span(), lim.spanMin(), lim.spanMax()), clamp(fr, 0, lim.maxSweepFraction()) };
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Default bounds from the body diameter at the fins: chord 0.75-3 D, span 0.5-2 D. */
	public static Limits defaults(TrapezoidFinSet f, double rootMin, double rootMax, double spanMin, double spanMax,
			double minTip, boolean allowOverhang, double maxSweepAngle) {
		double d = 2 * (f.getParent() instanceof SymmetricComponent s ? s.getAftRadius() : f.getBodyRadius());
		if (!(d > 0)) {
			d = 0.1;
		}
		double avail = f.getParent() == null ? Double.NaN : f.getParent().getLength();
		double rMax = Double.isNaN(rootMax) ? 3 * d : rootMax;
		if (!Double.isNaN(avail) && avail > 0) {
			rMax = Math.min(rMax, avail); // the root must fit on the tube it is mounted to
		}
		double rMin = Double.isNaN(rootMin) ? Math.min(0.75 * d, rMax * 0.5) : rootMin;
		double sMin = Double.isNaN(spanMin) ? 0.5 * d : spanMin, sMax = Double.isNaN(spanMax) ? 2 * d : spanMax;
		if (!(rMax > rMin) || !(sMax > sMin)) {
			throw new ToolException("Fin bounds are empty: root chord " + Units.fmt(rMin, Dim.LENGTH) + " to "
					+ Units.fmt(rMax, Dim.LENGTH) + ", span " + Units.fmt(sMin, Dim.LENGTH) + " to " + Units.fmt(sMax, Dim.LENGTH) + ".");
		}
		return new Limits(rMin, rMax, sMin, sMax, 0.2, 1.0, Double.isNaN(minTip) ? 0.0127 : minTip, allowOverhang ? 1.4 : 1.0,
				Double.isNaN(maxSweepAngle) ? Math.toRadians(65) : maxSweepAngle);
	}

	/** One searched thickness: the optimizer result with its planforms. */
	public record Run(double thickness, Optimizer.Result result) {
	}

	/**
	 * Runs the optimizer for each thickness (budget each) and returns the runs; the caller picks the best by score.
	 */
	public static List<Run> optimize(Simulation base, OpenRocketDocument doc, TrapezoidFinSet fin, Limits lim,
			List<Double> thicknesses, Optimizer.Objective o, double target, Optimizer.Constraints c, int budget,
			double windCase) {
		String id = fin.getID().toString();
		List<Optimizer.Variable> vars = List.of(
				new Optimizer.Variable(id, fin.getName(), "rootChord", lim.rootMin(), lim.rootMax()),
				new Optimizer.Variable(id, fin.getName(), "taperRatio", lim.taperMin(), lim.taperMax()),
				new Optimizer.Variable(id, fin.getName(), "height", lim.spanMin(), lim.spanMax()),
				new Optimizer.Variable(id, fin.getName(), "sweepFraction", 0, lim.maxSweepFraction()));
		List<Run> runs = new ArrayList<>();
		for (double t : thicknesses) {
			Optimizer.Applier ap = applier(id, lim, t);
			runs.add(new Run(t, Optimizer.run(base, doc, vars, ap, o, target, c, budget, 1, windCase)));
		}
		return runs;
	}

	/** Applies a planform; candidates fly on OpenRocket's drag model, which follows the fin shape (an imported table does not). */
	public static Optimizer.Applier applier(String finId, Limits lim, double thickness) {
		return Optimizer.Applier.withOpenRocketDrag(
				(Rocket r, double[] x) -> applyTo((TrapezoidFinSet) Components.find(r, finId), shape(x, lim, thickness)));
	}

	/** Planform and metrics of a point, for the report. */
	public static Map<String, Object> render(Optimizer.Point p, Planform f) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("rootChord", Units.fmt(f.root(), Dim.LENGTH));
		m.put("tipChord", Units.fmt(f.tip(), Dim.LENGTH));
		m.put("span", Units.fmt(f.span(), Dim.LENGTH));
		m.put("sweep", Units.fmt(f.sweep(), Dim.LENGTH) + " (leading edge " + Units.num(Math.toDegrees(f.sweepAngle())) + " deg)");
		m.put("thickness", Units.fmt(f.thickness(), Dim.LENGTH));
		m.put("areaPerFin", Units.fmt(f.area(), Dim.AREA));
		m.put("aspectRatio", Units.num(f.aspectRatio()));
		if (f.aftOverhang() > 1e-4) {
			m.put("aftOverhang", Units.fmt(f.aftOverhang(), Dim.LENGTH) + " (tip trailing edge behind the root: vulnerable on landing)");
		}
		if (p == null) {
			return m;
		}
		if (p.error() != null) {
			m.put("error", p.error());
			return m;
		}
		m.put("launchMass", Units.fmt(p.launchMass(), Dim.MASS));
		m.put("launchMargin", Units.num(p.launchMargin()) + " cal");
		if (p.simulated()) {
			m.put("apogee", Units.fmt(p.apogee(), Dim.DISTANCE));
			m.put("minAscentStability", Units.num(p.minStability()) + " cal");
			m.put("maxAscentStability", Units.num(p.maxStability()) + " cal");
			m.put("railExitVelocity", Units.fmt(p.railExit(), Dim.VELOCITY));
			m.put("maxMach", Units.num(p.maxMach()));
			if (!Double.isNaN(p.flutterMargin())) {
				m.put("finFlutterMargin", Units.num(p.flutterMargin()));
			}
		}
		return m;
	}

	/**
	 * Side view of both planforms on the same scale, roots on the body line. {@code alignAft}: the fins are positioned
	 * from the aft end of their tube (root trailing edges line up), otherwise from the front (leading edges line up).
	 */
	public static String svg(String title, Planform current, Planform best, boolean alignAft) {
		double w = 640, h = 360, pad = 50;
		double oc = alignAft ? -current.root() : 0, ob = alignAft ? -best.root() : 0;
		double minX = Math.min(oc, ob);
		double maxX = Math.max(oc + Math.max(current.root(), current.sweep() + current.tip()),
				ob + Math.max(best.root(), best.sweep() + best.tip()));
		double maxY = Math.max(current.span(), best.span());
		double sc = Math.min((w - 2 * pad) / (maxX - minX), (h - 2 * pad - 40) / maxY);
		double y0 = h - pad;
		StringBuilder s = new StringBuilder();
		s.append(String.format(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\" viewBox=\"0 0 %.0f %.0f\" "
				+ "font-family=\"sans-serif\">%n", w, h, w, h));
		s.append("<rect width=\"100%\" height=\"100%\" fill=\"#fcfcfb\"/>\n");
		s.append(String.format(Locale.ROOT, "<text x=\"%.0f\" y=\"24\" font-size=\"15\" font-weight=\"600\">%s</text>%n", pad, esc(title)));
		s.append(String.format(Locale.ROOT, "<line x1=\"%.0f\" x2=\"%.0f\" y1=\"%.1f\" y2=\"%.1f\" stroke=\"#555\" stroke-width=\"1.5\"/>%n",
				pad - 20, w - pad + 20, y0, y0));
		s.append(String.format(Locale.ROOT, "<text x=\"%.0f\" y=\"%.1f\" font-size=\"11\" fill=\"#555\">body tube, flow left to right "
				+ "(%s)</text>%n", pad, y0 + 16, alignAft ? "fins positioned from the aft end" : "fins positioned from the front"));
		s.append(poly(current, pad + (oc - minX) * sc, y0, sc, "#9aa4b2", "none", "6 4"));
		s.append(poly(best, pad + (ob - minX) * sc, y0, sc, "#2a6fdb", "rgba(42,111,219,0.15)", null));
		s.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"36\" width=\"14\" height=\"3\" fill=\"#9aa4b2\"/><text x=\"%.0f\" y=\"41\" "
				+ "font-size=\"12\">current: root %s, tip %s, span %s, %s thick</text>%n", pad, pad + 20, esc(len(current.root())),
				esc(len(current.tip())), esc(len(current.span())), esc(len(current.thickness()))));
		s.append(String.format(Locale.ROOT, "<rect x=\"%.0f\" y=\"54\" width=\"14\" height=\"3\" fill=\"#2a6fdb\"/><text x=\"%.0f\" y=\"59\" "
				+ "font-size=\"12\">optimized: root %s, tip %s, span %s, %s thick</text>%n", pad, pad + 20, esc(len(best.root())),
				esc(len(best.tip())), esc(len(best.span())), esc(len(best.thickness()))));
		s.append("</svg>\n");
		return s.toString();
	}

	private static String len(double m) {
		return Units.fmt(m, Dim.LENGTH).split(" \\(")[0];
	}

	private static String poly(Planform p, double x0, double y0, double sc, String stroke, String fill, String dash) {
		double[][] pts = { { 0, 0 }, { p.sweep(), p.span() }, { p.sweep() + p.tip(), p.span() }, { p.root(), 0 } };
		StringBuilder b = new StringBuilder();
		for (double[] q : pts) {
			b.append(String.format(Locale.ROOT, "%.1f,%.1f ", x0 + q[0] * sc, y0 - q[1] * sc));
		}
		return String.format(Locale.ROOT, "<polygon points=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"2\"%s/>%n", b.toString().trim(),
				fill, stroke, dash == null ? "" : " stroke-dasharray=\"" + dash + "\"");
	}

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
