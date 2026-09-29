package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.InternalComponent;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RingComponent;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Geometry;
import io.github.openrocketmcp.or.Loads;

/**
 * The rocket as coloured 3-D parts built from OpenRocket's geometry, for the exploded view and the flight animation.
 *
 * <p>Rocket frame as in OpenRocket: x along the axis from the nose tip aft, y and z across. Airframe pieces are hollow
 * shells (outer and inner surface, so a cut or an open end shows the wall); nose cone and transition shoulders belong to
 * their piece. Fins are thin plates. Internal parts (couplers, bulkheads, centering rings, motor mounts, parachutes,
 * shock cords, electronics and other masses, motors) are cylinders at their instances' positions.
 */
public final class Model3d {
	private Model3d() {
	}

	/** Paint: white airframe, red nose, blue fins; internal parts use the cut-away drawing's colours. */
	static final Map<String, Integer> COLOURS = colours();

	private static Map<String, Integer> colours() {
		Map<String, Integer> m = new HashMap<>();
		m.put("nose", 0xc8392f);
		m.put("body", 0xe9ecf1);
		m.put("transition", 0x3f6aa8);
		m.put("fins", 0x2d5d9f);
		m.put("struct", 0x8a96a8);
		m.put("tube", 0xaab4c3);
		m.put("chute", 0xf0a64a);
		m.put("cord", 0xf3d6a6);
		m.put("elec", 0x2e9e5b);
		m.put("batt", 0xe8c33c);
		m.put("track", 0x8e5cc9);
		m.put("charge", 0xd63b2f);
		m.put("switch", 0xf4f4f4);
		m.put("hw", 0xb9b3a8);
		m.put("payload", 0x4aa3c9);
		m.put("mass", 0x9b9b9b);
		m.put("motor", 0x4b4d52);
		m.put("nozzle", 0x26272a);
		return m;
	}

	/** One component's geometry (all its instances) in the rocket frame. */
	public static final class Part {
		public final RocketComponent component; // null for a motor
		public final String name, kind;
		public final boolean internal;
		/** Index of the airframe piece it belongs to (0 = the nose), in axial order; -1 before layout. */
		int piece = -1;
		final int stage;
		final List<double[]> tris = new ArrayList<>();
		double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, rMax;
		double mass;
		/** Exploded-view offset of the whole part (m, rocket frame). */
		double[] shift = { 0, 0, 0 };
		/** Fins only: one sub-list per fin with the fin's outward direction (for sliding fins out radially). */
		final List<List<double[]>> fins = new ArrayList<>();
		final List<double[]> finDirs = new ArrayList<>();

		Part(RocketComponent c, String name, String kind, boolean internal, int stage) {
			this.component = c;
			this.name = name;
			this.kind = kind;
			this.internal = internal;
			this.stage = stage;
		}

		public int rgb() {
			return COLOURS.getOrDefault(kind, 0xbbbbbb);
		}

		public double mass() {
			return mass;
		}

		void add(double[] t) {
			tris.add(t);
			for (int v = 0; v < 3; v++) {
				x0 = Math.min(x0, t[3 * v]);
				x1 = Math.max(x1, t[3 * v]);
				rMax = Math.max(rMax, Math.hypot(t[3 * v + 1], t[3 * v + 2]));
			}
		}

		/** Triangles with the exploded offsets applied ({@code finOut}: how far each fin slides out, m). */
		public List<double[]> placed(double finOut) {
			List<double[]> out = new ArrayList<>();
			if (!fins.isEmpty()) {
				for (int k = 0; k < fins.size(); k++) {
					double[] d = finDirs.get(k);
					for (double[] t : fins.get(k)) {
						out.add(offset(t, shift[0], shift[1] + d[1] * finOut, shift[2] + d[2] * finOut));
					}
				}
				return out;
			}
			if (shift[0] == 0 && shift[1] == 0 && shift[2] == 0) {
				return tris;
			}
			for (double[] t : tris) {
				out.add(offset(t, shift[0], shift[1], shift[2]));
			}
			return out;
		}
	}

	static double[] offset(double[] t, double dx, double dy, double dz) {
		return new double[] { t[0] + dx, t[1] + dy, t[2] + dz, t[3] + dx, t[4] + dy, t[5] + dz, t[6] + dx, t[7] + dy, t[8] + dz };
	}

	/** Every part of the active configuration, airframe pieces first in axial order. */
	public static List<Part> build(FlightConfiguration fc, int segments) {
		Analysis.settle(fc);
		Map<RocketComponent, double[]> masses = Loads.componentMasses(fc, null);
		List<Part> out = new ArrayList<>();
		Map<RocketComponent, Part> pieces = new LinkedHashMap<>();
		for (RocketComponent c : fc.getAllActiveComponents()) {
			int stage = c instanceof Rocket ? 0 : c.getStage().getStageNumber();
			Part p = null;
			if (c instanceof SymmetricComponent sc && sc.getLength() > 0 && !(c instanceof InternalComponent)) {
				p = new Part(c, c.getName(), c instanceof NoseCone ? "nose" : c instanceof Transition ? "transition" : "body", false, stage);
				for (Coordinate a : c.toAbsolute(Coordinate.NUL)) {
					shell(p, sc, a, segments);
				}
				pieces.put(c, p);
			} else if (c instanceof FinSet f) {
				p = new Part(c, c.getName(), "fins", false, stage);
				fins(p, f);
			} else if (c instanceof InternalComponent) {
				p = internal(c, stage, segments);
			}
			if (p != null && !p.tris.isEmpty()) {
				double[] m = masses.get(c);
				p.mass = m != null ? m[0] : c.getComponentMass() * Math.max(1, c.getInstanceCount());
				out.add(p);
			}
		}
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() == null) {
				continue;
			}
			RocketComponent mount = (RocketComponent) mc.getMount();
			Part p = new Part(null, mc.getMotor().getDesignation(), "motor", true, mount.getStage().getStageNumber());
			double ml = mc.getMotor().getLength(), mr = mc.getMotor().getDiameter() / 2;
			for (Coordinate a : mount.toAbsolute(Coordinate.NUL)) {
				double aft = a.x + mount.getLength() + mc.getMount().getMotorOverhang();
				cylinder(p, aft - ml, aft - 0.06 * ml, 0, mr, a.y, a.z, segments);
				// Nozzle: a short dark cone at the aft end.
				lathe(p, new double[][] { { aft - 0.06 * ml, 0 }, { aft - 0.06 * ml, mr }, { aft, 0.62 * mr }, { aft, 0.4 * mr },
						{ aft - 0.03 * ml, 0.3 * mr } }, a.y, a.z, segments, true);
			}
			p.mass = mc.getMotor().getLaunchMass();
			out.add(p);
		}
		// Airframe piece of every part: the stage child that holds it (pods and boosters travel with their own pieces).
		List<RocketComponent> order = new ArrayList<>(pieces.keySet());
		order.sort((a, b) -> Double.compare(a.toAbsolute(Coordinate.NUL)[0].x, b.toAbsolute(Coordinate.NUL)[0].x));
		for (Part p : out) {
			RocketComponent owner = p.component != null ? pieceOf(p.component) : null;
			if (p.component == null) { // motor: the piece holding its mount
				for (MotorConfiguration mc : fc.getActiveMotors()) {
					if (mc.getMotor() != null && mc.getMotor().getDesignation().equals(p.name)) {
						owner = pieceOf((RocketComponent) mc.getMount());
						break;
					}
				}
			}
			p.piece = Math.max(0, order.indexOf(owner));
		}
		out.sort((a, b) -> a.internal != b.internal ? (a.internal ? 1 : -1) : Integer.compare(a.piece, b.piece));
		return out;
	}

	static RocketComponent pieceOf(RocketComponent c) {
		RocketComponent p = c;
		while (p.getParent() != null && !(p.getParent() instanceof AxialStage)) {
			p = p.getParent();
		}
		return p;
	}

	private static Part internal(RocketComponent c, int stage, int seg) {
		String kind;
		double len, rOut, rIn = 0;
		if (c instanceof RecoveryDevice rd) {
			kind = "chute";
			len = rd.getLength();
			rOut = rd.getRadius();
		} else if (c instanceof ShockCord sc) {
			kind = "cord";
			len = sc.getLength();
			rOut = sc.getRadius();
		} else if (c instanceof MassComponent m) {
			kind = Drawing.massClass(m);
			len = m.getLength();
			rOut = m.getRadius();
		} else if (c instanceof RingComponent rc) {
			kind = c instanceof InnerTube || c instanceof TubeCoupler ? "tube" : "struct";
			len = rc.getLength();
			rOut = rc.getOuterRadius();
			rIn = rc.getInnerRadius();
		} else {
			return null;
		}
		if (len <= 0 || rOut <= 0) {
			return null;
		}
		Part p = new Part(c, c.getName(), kind, true, stage);
		for (Coordinate a : c.toAbsolute(Coordinate.NUL)) {
			cylinder(p, a.x, a.x + len, rIn, rOut, a.y, a.z, seg);
		}
		return p;
	}

	/** Outer surface, inner surface (unless filled) and end rings of a nose cone, tube or transition, with shoulders. */
	private static void shell(Part p, SymmetricComponent sc, Coordinate a, int seg) {
		int n = sc instanceof Transition ? 40 : 1;
		double len = sc.getLength();
		List<double[]> outer = new ArrayList<>(), inner = new ArrayList<>();
		for (int i = 0; i <= n; i++) {
			double u = (double) i / n;
			double x = len * (n == 1 ? u : 0.5 * (1 - Math.cos(Math.PI * u)));
			double r = sc.getRadius(x);
			outer.add(new double[] { a.x + x, r });
			double ri = sc.isFilled() ? 0 : Math.max(0, Math.min(r, sc.getInnerRadius(x)));
			inner.add(0, new double[] { a.x + x, ri });
		}
		List<double[]> profile = new ArrayList<>(outer);
		profile.addAll(inner);
		lathe(p, profile.toArray(new double[0][]), a.y, a.z, seg, true);
		if (sc instanceof Transition t) {
			if (t.getAftShoulderLength() > 0 && t.getAftShoulderRadius() > 0) {
				double r = t.getAftShoulderRadius(), ri = Math.max(0, r - t.getAftShoulderThickness());
				cylinder(p, a.x + len, a.x + len + t.getAftShoulderLength(), t.isAftShoulderCapped() ? 0 : ri, r, a.y, a.z, seg);
			}
			if (t.getForeShoulderLength() > 0 && t.getForeShoulderRadius() > 0) {
				double r = t.getForeShoulderRadius(), ri = Math.max(0, r - t.getForeShoulderThickness());
				cylinder(p, a.x - t.getForeShoulderLength(), a.x, t.isForeShoulderCapped() ? 0 : ri, r, a.y, a.z, seg);
			}
		}
	}

	/** A tube (or a solid cylinder when rIn = 0) from x0 to x1. */
	static void cylinder(Part p, double x0, double x1, double rIn, double rOut, double cy, double cz, int seg) {
		lathe(p, new double[][] { { x0, rIn }, { x0, rOut }, { x1, rOut }, { x1, rIn } }, cy, cz, seg, rIn > 1e-9);
	}

	/**
	 * Revolves a profile of (x, r) points about the axis at (cy, cz). A closed profile (a wall section) is closed back
	 * to its first point; points on the axis end the surface there.
	 */
	static void lathe(Part p, double[][] prof, double cy, double cz, int seg, boolean closed) {
		int m = prof.length;
		for (int i = 0; i < (closed ? m : m - 1); i++) {
			double[] a = prof[i], b = prof[(i + 1) % m];
			if (a[1] < 1e-9 && b[1] < 1e-9) {
				continue; // along the axis
			}
			for (int j = 0; j < seg; j++) {
				double t0 = 2 * Math.PI * j / seg, t1 = 2 * Math.PI * (j + 1) / seg;
				double[] p00 = ring(a[0], a[1], cy, cz, t0), p10 = ring(b[0], b[1], cy, cz, t0);
				double[] p11 = ring(b[0], b[1], cy, cz, t1), p01 = ring(a[0], a[1], cy, cz, t1);
				if (a[1] > 1e-9) {
					p.add(new double[] { p00[0], p00[1], p00[2], p01[0], p01[1], p01[2], p10[0], p10[1], p10[2] });
				}
				if (b[1] > 1e-9) {
					p.add(new double[] { p10[0], p10[1], p10[2], p01[0], p01[1], p01[2], p11[0], p11[1], p11[2] });
				}
			}
		}
	}

	private static double[] ring(double x, double r, double cy, double cz, double th) {
		return new double[] { x, cy + r * Math.cos(th), cz + r * Math.sin(th) };
	}

	/** Each fin of the set as a closed plate at its angle, on every instance of the body it is attached to. */
	private static void fins(Part p, FinSet f) {
		Coordinate[] pts = f.getFinPoints();
		double rBody = f.getBodyRadius(), half = Math.max(f.getThickness(), 1e-4) / 2;
		double fx = f.toAbsolute(Coordinate.NUL)[0].x;
		List<double[]> poly = new ArrayList<>();
		for (Coordinate q : pts) {
			poly.add(new double[] { q.x, Math.max(0, q.y) });
		}
		if (Geometry.area(poly) < 0) {
			Collections.reverse(poly);
		}
		List<int[]> faces = Geometry.triangulate(poly);
		int n = f.getFinCount();
		List<String> seen = new ArrayList<>();
		for (Coordinate axis : f.getParent().toAbsolute(Coordinate.NUL)) {
			String key = Math.round(axis.y * 1e4) + ":" + Math.round(axis.z * 1e4);
			if (seen.contains(key)) {
				continue;
			}
			seen.add(key);
			for (int k = 0; k < n; k++) {
				double th = f.getBaseRotation() + 2 * Math.PI * k / n;
				double cy = Math.cos(th), cz = Math.sin(th), ty = -Math.sin(th), tz = Math.cos(th);
				List<double[]> fin = new ArrayList<>();
				BiFunction<double[], Double, double[]> at = (q, side) -> new double[] { fx + q[0],
						axis.y + (rBody + q[1]) * cy + side * ty, axis.z + (rBody + q[1]) * cz + side * tz };
				for (int[] t : faces) {
					fin.add(tri(at.apply(poly.get(t[0]), half), at.apply(poly.get(t[1]), half), at.apply(poly.get(t[2]), half)));
					fin.add(tri(at.apply(poly.get(t[0]), -half), at.apply(poly.get(t[2]), -half), at.apply(poly.get(t[1]), -half)));
				}
				for (int i = 0; i < poly.size(); i++) {
					double[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
					double[] a1 = at.apply(a, half), b1 = at.apply(b, half), b2 = at.apply(b, -half), a2 = at.apply(a, -half);
					fin.add(tri(a1, a2, b2));
					fin.add(tri(a1, b2, b1));
				}
				for (double[] t : fin) {
					p.add(t);
				}
				p.fins.add(fin);
				p.finDirs.add(new double[] { 0, cy, cz });
			}
		}
	}

	private static double[] tri(double[] a, double[] b, double[] c) {
		return new double[] { a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2] };
	}
}
