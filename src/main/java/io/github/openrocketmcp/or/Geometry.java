package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.util.Coordinate;

/**
 * Geometry for tools outside OpenRocket: the outer mold line as STL (CFD meshers, CAD), fin flat patterns as DXF
 * (waterjet / laser / CNC), and the flight conditions a CFD study should run.
 *
 * <p>STL frame: x along the axis from the nose tip toward the tail (the freestream flows in +x), y and z across. The body
 * is written as one lateral-surface solid per component plus a base cap; together they close (shared seam rings), so a
 * mesher can report forces per component. Fins are separate closed plates whose roots sink slightly into the body so a
 * boolean union or snappyHexMesh sees them attached.
 */
public final class Geometry {
	private Geometry() {
	}

	/** Triangles of one named solid. */
	public static final class Solid {
		public final String name;
		public final List<double[]> tris = new ArrayList<>(); // 9 numbers each: three vertices

		Solid(String name) {
			this.name = name;
		}

		void tri(double[] a, double[] b, double[] c) {
			double[] n = normal(a, b, c);
			if (n == null) {
				return; // degenerate (e.g. at the nose tip)
			}
			tris.add(new double[] { a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2] });
		}
	}

	static double[] normal(double[] a, double[] b, double[] c) {
		double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return l < 1e-18 ? null : new double[] { nx / l, ny / l, nz / l };
	}

	/** The outer mold line of the active configuration (m). */
	public static List<Solid> solids(FlightConfiguration fc, int segments) {
		Analysis.settle(fc);
		List<Solid> out = new ArrayList<>();
		// Group body pieces by the axis they sit on (the main stack, or each pod), in axial order.
		Map<String, List<Object[]>> axes = new LinkedHashMap<>();
		for (RocketComponent c : fc.getActiveComponents()) {
			if (c instanceof SymmetricComponent sc && sc.getLength() > 0 && !isInternal(c)) {
				for (Coordinate a : c.toAbsolute(Coordinate.NUL)) {
					String key = Math.round(a.y * 1e4) + ":" + Math.round(a.z * 1e4);
					axes.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[] { sc, a });
				}
			}
		}
		int axisNo = 0;
		for (List<Object[]> pieces : axes.values()) {
			pieces.sort((p, q) -> Double.compare(((Coordinate) p[1]).x, ((Coordinate) q[1]).x));
			String suffix = axes.size() > 1 && axisNo > 0 ? "_pod" + axisNo : "";
			double[] prevRing = null; // x, r of the previous aft end
			for (Object[] p : pieces) {
				SymmetricComponent sc = (SymmetricComponent) p[0];
				Coordinate a = (Coordinate) p[1];
				Solid s = new Solid(safe(sc.getName()) + suffix);
				int n = sc instanceof info.openrocket.core.rocketcomponent.BodyTube ? 1 : 48;
				double[] xs = new double[n + 1];
				for (int i = 0; i <= n; i++) {
					double u = (double) i / n;
					xs[i] = sc.getLength() * (n == 1 ? u : 0.5 * (1 - Math.cos(Math.PI * u))); // cluster at both ends
				}
				double r0 = sc.getRadius(0);
				double front = a.x;
				if (prevRing == null && r0 > 1e-9) {
					disk(s, front, a.y, a.z, r0, segments, false); // no nose: close the front
				} else if (prevRing != null && Math.abs(prevRing[1] - r0) > 1e-6) {
					annulus(s, front, a.y, a.z, prevRing[1], r0, segments); // a step between pieces
				}
				for (int i = 0; i < n; i++) {
					revolve(s, front + xs[i], sc.getRadius(xs[i]), front + xs[i + 1], sc.getRadius(xs[i + 1]), a.y, a.z, segments);
				}
				prevRing = new double[] { front + sc.getLength(), sc.getRadius(sc.getLength()) };
				out.add(s);
			}
			if (prevRing != null && prevRing[1] > 1e-9) {
				Solid base = new Solid("base" + suffix);
				Coordinate a = (Coordinate) pieces.get(pieces.size() - 1)[1];
				disk(base, prevRing[0], a.y, a.z, prevRing[1], segments, true);
				out.add(base);
			}
			axisNo++;
		}
		for (RocketComponent c : fc.getActiveComponents()) {
			if (c instanceof FinSet f) {
				out.add(fins(f));
			}
		}
		return out;
	}

	private static boolean isInternal(RocketComponent c) {
		return c instanceof info.openrocket.core.rocketcomponent.InternalComponent;
	}

	public static String safe(String name) {
		String s = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
		return s.isEmpty() ? "part" : s;
	}

	private static double[] ring(double x, double r, double cy, double cz, double th) {
		return new double[] { x, cy + r * Math.cos(th), cz + r * Math.sin(th) };
	}

	/** Lateral band between two stations; outward normals. */
	static void revolve(Solid s, double x0, double r0, double x1, double r1, double cy, double cz, int seg) {
		for (int j = 0; j < seg; j++) {
			double t0 = 2 * Math.PI * j / seg, t1 = 2 * Math.PI * (j + 1) / seg;
			double[] a = ring(x0, r0, cy, cz, t0), b = ring(x1, r1, cy, cz, t0), c = ring(x1, r1, cy, cz, t1),
					d = ring(x0, r0, cy, cz, t1);
			s.tri(a, d, b);
			s.tri(b, d, c);
		}
	}

	/** Disk facing -x (front) or +x (aft). */
	static void disk(Solid s, double x, double cy, double cz, double r, int seg, boolean aft) {
		double[] c = { x, cy, cz };
		for (int j = 0; j < seg; j++) {
			double[] a = ring(x, r, cy, cz, 2 * Math.PI * j / seg), b = ring(x, r, cy, cz, 2 * Math.PI * (j + 1) / seg);
			if (aft) {
				s.tri(c, a, b);
			} else {
				s.tri(c, b, a);
			}
		}
	}

	/** Flat step from radius rFrom (the piece ahead) to rTo (this piece) at x; normal points out of the body. */
	static void annulus(Solid s, double x, double cy, double cz, double rFrom, double rTo, int seg) {
		// This winding faces -x when the radius grows (a forward-facing shoulder) and +x when it shrinks.
		for (int j = 0; j < seg; j++) {
			double t0 = 2 * Math.PI * j / seg, t1 = 2 * Math.PI * (j + 1) / seg;
			double[] a = ring(x, rFrom, cy, cz, t0), b = ring(x, rTo, cy, cz, t0), c = ring(x, rTo, cy, cz, t1),
					d = ring(x, rFrom, cy, cz, t1);
			s.tri(a, d, b);
			s.tri(b, d, c);
		}
	}

	/** Every fin of a set as a closed flat plate, root sunk 2% of the body radius into the body. */
	static Solid fins(FinSet f) {
		Solid s = new Solid(safe(f.getName()));
		Coordinate[] pts = f.getFinPoints();
		double rBody = f.getBodyRadius(), sink = Math.max(1e-4, 0.02 * rBody), half = f.getThickness() / 2;
		double fx = f.toAbsolute(Coordinate.NUL)[0].x;
		List<double[]> poly = new ArrayList<>();
		for (Coordinate p : pts) {
			poly.add(new double[] { p.x, p.y < 1e-9 ? -sink : p.y });
		}
		if (area(poly) < 0) {
			java.util.Collections.reverse(poly);
		}
		List<int[]> faces = triangulate(poly);
		int n = f.getFinCount();
		for (Coordinate axis : uniqueAxes(f.getParent().toAbsolute(Coordinate.NUL))) {
			for (int k = 0; k < n; k++) {
				double th = f.getBaseRotation() + 2 * Math.PI * k / n;
				double cy = Math.cos(th), cz = Math.sin(th), ty = -Math.sin(th), tz = Math.cos(th);
				java.util.function.BiFunction<double[], Double, double[]> at = (q, side) -> new double[] { fx + q[0],
						axis.y + (rBody + q[1]) * cy + side * ty, axis.z + (rBody + q[1]) * cz + side * tz };
				for (int[] t : faces) { // the two faces
					s.tri(at.apply(poly.get(t[0]), half), at.apply(poly.get(t[1]), half), at.apply(poly.get(t[2]), half));
					s.tri(at.apply(poly.get(t[0]), -half), at.apply(poly.get(t[2]), -half), at.apply(poly.get(t[1]), -half));
				}
				for (int i = 0; i < poly.size(); i++) { // the edges
					double[] p = poly.get(i), q = poly.get((i + 1) % poly.size());
					double[] a = at.apply(p, half), b = at.apply(q, half), c = at.apply(q, -half), d = at.apply(p, -half);
					s.tri(a, d, c);
					s.tri(a, c, b);
				}
			}
		}
		return s;
	}

	private static List<Coordinate> uniqueAxes(Coordinate[] cs) {
		List<Coordinate> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Coordinate c : cs) {
			if (seen.add(Math.round(c.y * 1e4) + ":" + Math.round(c.z * 1e4))) {
				out.add(c);
			}
		}
		return out;
	}

	static double area(List<double[]> p) {
		double a = 0;
		for (int i = 0; i < p.size(); i++) {
			double[] u = p.get(i), v = p.get((i + 1) % p.size());
			a += u[0] * v[1] - v[0] * u[1];
		}
		return a / 2;
	}

	/** Ear clipping of a simple counter-clockwise polygon (fin outlines can be concave). */
	static List<int[]> triangulate(List<double[]> p) {
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < p.size(); i++) {
			idx.add(i);
		}
		List<int[]> out = new ArrayList<>();
		int guard = 0;
		while (idx.size() > 3 && guard++ < 10000) {
			boolean cut = false;
			for (int i = 0; i < idx.size(); i++) {
				int ia = idx.get((i + idx.size() - 1) % idx.size()), ib = idx.get(i), ic = idx.get((i + 1) % idx.size());
				double[] a = p.get(ia), b = p.get(ib), c = p.get(ic);
				double cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
				if (cross <= 1e-15) {
					if (Math.abs(cross) <= 1e-15) { // collinear: drop the middle point
						idx.remove(i);
						cut = true;
						break;
					}
					continue;
				}
				boolean inside = false;
				for (int j : idx) {
					if (j != ia && j != ib && j != ic && inTriangle(p.get(j), a, b, c)) {
						inside = true;
						break;
					}
				}
				if (!inside) {
					out.add(new int[] { ia, ib, ic });
					idx.remove(i);
					cut = true;
					break;
				}
			}
			if (!cut) {
				break;
			}
		}
		if (idx.size() == 3) {
			out.add(new int[] { idx.get(0), idx.get(1), idx.get(2) });
		}
		return out;
	}

	private static boolean inTriangle(double[] p, double[] a, double[] b, double[] c) {
		double d1 = side(p, a, b), d2 = side(p, b, c), d3 = side(p, c, a);
		return d1 > 0 && d2 > 0 && d3 > 0;
	}

	private static double side(double[] p, double[] a, double[] b) {
		return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
	}

	/** ASCII STL, one "solid" per part, scaled (1 = metres, 1000 = millimetres). */
	public static String stl(List<Solid> solids, double scale) {
		StringBuilder b = new StringBuilder();
		for (Solid s : solids) {
			b.append("solid ").append(s.name).append('\n');
			for (double[] t : s.tris) {
				double[] n = normal(new double[] { t[0], t[1], t[2] }, new double[] { t[3], t[4], t[5] }, new double[] { t[6], t[7], t[8] });
				b.append(String.format(Locale.ROOT, "  facet normal %.6e %.6e %.6e%n    outer loop%n", n[0], n[1], n[2]));
				for (int v = 0; v < 3; v++) {
					b.append(String.format(Locale.ROOT, "      vertex %.6e %.6e %.6e%n", t[3 * v] * scale, t[3 * v + 1] * scale,
							t[3 * v + 2] * scale));
				}
				b.append("    endloop\n  endfacet\n");
			}
			b.append("endsolid ").append(s.name).append('\n');
		}
		return b.toString();
	}

	/**
	 * Flat pattern of one fin (with its through-the-wall tab if it has one) as DXF R12 LINE entities, root on y = 0,
	 * leading edge of the root at x = 0; a second layer marks the root line (the body surface) for tabbed fins.
	 */
	public static String dxf(FinSet f, double scale) {
		Coordinate[] pts = f.getTabHeight() > 1e-9 && f.getTabLength() > 1e-9 ? f.generateContinuousFinAndTabShape() : f.getFinPoints();
		StringBuilder b = new StringBuilder("0\nSECTION\n2\nHEADER\n9\n$ACADVER\n1\nAC1009\n0\nENDSEC\n0\nSECTION\n2\nENTITIES\n");
		for (int i = 0; i < pts.length; i++) {
			Coordinate p = pts[i], q = pts[(i + 1) % pts.length];
			if (Math.hypot(q.x - p.x, q.y - p.y) < 1e-12) {
				continue;
			}
			line(b, "FIN_OUTLINE", p.x * scale, p.y * scale, q.x * scale, q.y * scale);
		}
		if (f.getTabHeight() > 1e-9 && f.getTabLength() > 1e-9) {
			Coordinate[] fin = f.getFinPoints();
			line(b, "ROOT_LINE", fin[0].x * scale, 0, fin[fin.length - 1].x * scale, 0);
		}
		b.append("0\nENDSEC\n0\nEOF\n");
		return b.toString();
	}

	private static void line(StringBuilder b, String layer, double x0, double y0, double x1, double y1) {
		b.append(String.format(Locale.ROOT, "0\nLINE\n8\n%s\n10\n%.6f\n20\n%.6f\n30\n0.0\n11\n%.6f\n21\n%.6f\n31\n0.0\n", layer, x0, y0, x1, y1));
	}

	// ------------------------------------------------------------------------------------------- CFD cases

	/** One flight condition to run in CFD. */
	public record Case(String name, double mach, double altitude, double velocity, double pressure, double temperature,
			double density, double viscosity, double reynolds, double orCd, double orCpX) {
	}

	/** Sutherland's law for air. */
	public static double viscosity(double temperature) {
		return 1.458e-6 * Math.pow(temperature, 1.5) / (temperature + 110.4);
	}

	static final double[] MACHS = { 0.1, 0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 1.0, 1.05, 1.1, 1.2, 1.5, 2.0, 2.5, 3.0 };

	/**
	 * The Mach sweep for an aero table (up to just past the flight's maximum Mach), each at the altitude and air state
	 * where the simulated ascent first reaches it, plus the maximum-q and maximum-Mach points. Reynolds number on the
	 * body length. OpenRocket's own CD and CP at each Mach come along for comparison.
	 */
	public static List<Case> cases(Simulation sim, FlightConfiguration fc) {
		Branch br = Branch.of(sim.getSimulatedData().getBranch(0));
		double[] t = br.time, mach = br.col(FlightDataType.TYPE_MACH_NUMBER), alt = br.col(FlightDataType.TYPE_ALTITUDE),
				p = br.col(FlightDataType.TYPE_AIR_PRESSURE), temp = br.col(FlightDataType.TYPE_AIR_TEMPERATURE),
				rho = br.col(FlightDataType.TYPE_AIR_DENSITY), a = br.col(FlightDataType.TYPE_SPEED_OF_SOUND);
		double length = fc.getLength();
		int iMax = 0, iQ = 0;
		double qMax = 0;
		for (int i = 0; i < t.length; i++) {
			if (!Double.isNaN(mach[i]) && (Double.isNaN(mach[iMax]) || mach[i] > mach[iMax])) {
				iMax = i;
			}
			double v = br.airspeed(i), q = 0.5 * rho[i] * v * v;
			if (!Double.isNaN(q) && q > qMax) {
				qMax = q;
				iQ = i;
			}
		}
		List<double[]> wanted = new ArrayList<>(); // mach, index, kind (0 sweep, 1 max q, 2 max Mach)
		double top = mach[iMax];
		for (double m : MACHS) {
			if (m > top * 1.1 + 0.05) {
				break;
			}
			int at = iMax;
			for (int i = 0; i <= iMax; i++) {
				if (mach[i] >= m) {
					at = i;
					break;
				}
			}
			wanted.add(new double[] { m, at, 0 });
		}
		wanted.add(new double[] { mach[iQ], iQ, 1 });
		wanted.add(new double[] { top, iMax, 2 });
		List<Aero.Point> aero = Aero.sweep(fc, wanted.stream().mapToDouble(w -> w[0]).toArray());
		List<Case> out = new ArrayList<>();
		for (int k = 0; k < wanted.size(); k++) {
			double[] w = wanted.get(k);
			int i = (int) w[1];
			double m = w[0], v = m * a[i], mu = viscosity(temp[i]);
			String name = w[2] == 1 ? "max_q" : w[2] == 2 ? "max_mach" : String.format(Locale.ROOT, "M%.2f", m);
			out.add(new Case(name, m, alt[i], v, p[i], temp[i], rho[i], mu, rho[i] * v * length / mu, aero.get(k).cd(),
					aero.get(k).cpX()));
		}
		return out;
	}

	public static String casesCsv(List<Case> cases, double[] aoaDeg) {
		StringBuilder b = new StringBuilder("case,mach,aoa_deg,altitude_m,velocity_m_s,static_pressure_pa,temperature_k,density_kg_m3,"
				+ "viscosity_pa_s,reynolds_body_length,openrocket_cd,openrocket_cp_m\n");
		for (Case c : cases) {
			for (double aoa : aoaDeg) {
				b.append(String.format(Locale.ROOT, "%s,%.3f,%.1f,%.1f,%.2f,%.1f,%.2f,%.5f,%.4e,%.4e,%.4f,%.4f%n", c.name(), c.mach(), aoa,
						c.altitude(), c.velocity(), c.pressure(), c.temperature(), c.density(), c.viscosity(), c.reynolds(),
						c.orCd(), c.orCpX()));
			}
		}
		return b.toString();
	}

	/** The file CFD results go back in through import_aero_table (CP in metres from the nose tip). */
	public static String resultsTemplate(List<Case> cases) {
		StringBuilder b = new StringBuilder("Mach,Alpha,CD Power-Off,CD Power-On,CP\n");
		java.util.TreeSet<Double> machs = new java.util.TreeSet<>();
		for (Case c : cases) {
			if (c.name().startsWith("M")) {
				machs.add(c.mach());
			}
		}
		for (double m : machs) {
			b.append(String.format(Locale.ROOT, "%.3f,0,,,%n", m));
		}
		return b.toString();
	}
}
