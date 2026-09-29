package io.github.openrocketmcp.report;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.ParallelStage;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.or.FlightTrack;
import io.github.openrocketmcp.or.FlightTrack.Event;
import io.github.openrocketmcp.or.FlightTrack.Flight;
import io.github.openrocketmcp.or.FlightTrack.Track;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.UnitSystem;
import io.github.openrocketmcp.units.Units;

/**
 * A short 3-D animation of a simulated flight: a chase camera follows the rocket (built from the design's own
 * geometry, rolling as simulated, with an exhaust flame while the motor burns and a smoke trail left in the sky); at
 * each deployment the airframe comes apart at its separation joints and hangs under the canopy as it inflates. A
 * heads-up display gives the flight clock, altitude, speed, vertical speed, Mach, acceleration and distance from the
 * pad; captions call out liftoff, rail clearance, burnout, Mach 1, maximum velocity, apogee, each deployment and
 * touchdown; insets show the whole trajectory in 3-D and the altitude trace; a timeline marks every event.
 *
 * <p>Playback runs at real time through the burn and slows back down around apogee and each deployment; the coast and
 * the descent are sped up so the whole flight fits the requested length. The playback rate is always on screen.
 */
public final class FlightAnimation {
	static final double PRE = 1.2, HOLD = 3.5, FOV = 40;

	public record Options(int width, double fps, double duration, String title, String subtitle) {
	}

	private final Flight f;
	private final Options o;
	public final int w, h;
	private final double k; // font scale
	// Schedule: flight time of each frame and the density (animation seconds per flight second) behind it.
	private final double[] gridT, gridA, gridD;
	private final double animEnd;
	public final int frames;
	// Scene.
	private final Map<Integer, List<Raster3d.Mesh>> sectionMeshes = new HashMap<>(); // main-stack section -> meshes
	private final Map<Integer, Raster3d.Mesh[]> stageMeshes = new HashMap<>(); // dropped stage -> meshes
	private final Map<Integer, double[]> stagePivot = new HashMap<>();
	private final Map<Integer, Integer> trackStage = new HashMap<>(); // track index -> stage number
	private final List<double[]> sections = new ArrayList<>(); // {x start, x end} of each main-stack section
	private final double length, maxR, cg0, lift, railLength;
	/** Stage -> its nozzles {x, y, z, radius} in the rocket frame, one per motor instance. */
	private final Map<Integer, List<double[]>> nozzles = new HashMap<>();
	private final Set<Integer> sideBoosters = new HashSet<>(); // parallel stages (they burn with the core)
	private final double[] drift, side, axis0;
	private final List<Event> deploys = new ArrayList<>();
	private final Raster3d.Mesh pad, rail, flameOuter, flameInner;
	private final Raster3d.Mesh[][] canopies; // per deployment: two gore colours
	private final List<double[]> smoke = new ArrayList<>(); // t, x, y, z

	public FlightAnimation(Flight f, FlightConfiguration fc, double railLength, Options o) {
		this.f = f;
		this.o = o;
		this.w = (o.width() / 2) * 2;
		this.h = ((int) Math.round(w * 9 / 16.0) / 2) * 2;
		this.k = w / 1280.0;
		this.railLength = railLength;
		Track m = f.main();
		double tEnd = Math.max(m.end(), 0.1);

		// ---- time warp
		double burn = Math.max(f.burnout(), 0.3), apogee = Math.max(f.apogeeTime(), burn + 0.1);
		double boostEnd = Math.min(apogee, Math.max(burn + 0.8, 1.2));
		List<Double> slow = new ArrayList<>(List.of(apogee));
		for (Event e : f.events()) {
			if (e.kind().equals("deploy")) {
				deploys.add(e);
				slow.add(e.time());
			}
		}
		double budget = Math.max(4, o.duration() - PRE - HOLD - boostEnd - 1.6 * slow.size());
		double dc = Math.min(1, 0.42 * budget / Math.max(0.1, apogee - boostEnd));
		double dd = Math.min(1, 0.58 * budget / Math.max(0.1, tEnd - apogee));
		int n = (int) Math.ceil(tEnd / 0.01) + 1;
		gridT = new double[n];
		double[] raw = new double[n];
		for (int i = 0; i < n; i++) {
			double t = Math.min(tEnd, i * 0.01);
			gridT[i] = t;
			double d = t < boostEnd ? 1 : t < apogee ? dc : dd;
			for (double s : slow) {
				if (t > s - 0.6 && t < s + 2.2) {
					d = Math.max(d, 0.5);
				}
			}
			raw[i] = d;
		}
		gridD = new double[n];
		int half = 40; // smooth over +-0.4 s so the playback rate never jumps
		double acc = 0;
		for (int i = 0; i < n; i++) {
			double s = 0;
			int c = 0;
			for (int j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++) {
				s += raw[j];
				c++;
			}
			gridD[i] = gridT[i] < boostEnd - 0.4 ? 1 : s / c;
		}
		gridA = new double[n];
		for (int i = 1; i < n; i++) {
			acc += 0.5 * (gridD[i] + gridD[i - 1]) * (gridT[i] - gridT[i - 1]);
			gridA[i] = acc;
		}
		animEnd = acc;
		frames = (int) Math.ceil((PRE + animEnd + HOLD) * o.fps());

		// ---- scene
		List<Model3d.Part> parts = Model3d.build(fc, 40);
		parts.removeIf(p -> p.internal && !p.kind.equals("motor"));
		double r = 0;
		for (Model3d.Part p : parts) {
			r = Math.max(r, p.kind.equals("fins") ? 0 : p.rMax);
		}
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() == null) {
				continue;
			}
			RocketComponent mount = (RocketComponent) mc.getMount();
			int st = mount.getStage().getStageNumber();
			if (mount.getStage() instanceof ParallelStage) {
				sideBoosters.add(st);
			}
			for (Coordinate a : mount.toAbsolute(Coordinate.NUL)) {
				double aft = a.x + mount.getLength() + mc.getMount().getMotorOverhang();
				nozzles.computeIfAbsent(st, x -> new ArrayList<>()).add(new double[] { aft, a.y, a.z,
						Math.max(0.005, 0.25 * mc.getMotor().getDiameter()) });
			}
		}
		length = Math.max(0.1, fc.getLength());
		maxR = Math.max(0.01, r);
		// Stages dropped in flight fly their own track (branch names are the stage names).
		Map<String, Integer> stageByName = new HashMap<>();
		for (RocketComponent c : fc.getRocket().getChildren()) {
			if (c instanceof AxialStage st) {
				stageByName.put(st.getName(), st.getStageNumber());
			}
		}
		for (int i = 1; i < f.tracks().size(); i++) {
			Integer st = stageByName.get(f.tracks().get(i).name);
			if (st != null && st > 0) {
				trackStage.put(i, st);
			}
		}
		// Sections of the main stack between separation joints.
		List<Double> joints = Drawing.separations(fc);
		double stack0 = Double.MAX_VALUE, stack1 = 0;
		Map<Integer, Double> pieceStart = new HashMap<>();
		for (Model3d.Part p : parts) {
			if (!p.internal && !p.kind.equals("fins")) {
				pieceStart.merge(p.piece, p.x0, Math::min);
			}
			if (!trackStage.containsValue(p.stage)) {
				stack0 = Math.min(stack0, p.x0);
				stack1 = Math.max(stack1, p.x1);
			}
		}
		double lo = stack0, hi = stack1;
		joints.removeIf(x -> x <= lo + 1e-3 || x >= hi - 1e-3);
		joints.sort(Double::compare);
		double prev = stack0;
		for (double x : joints) {
			sections.add(new double[] { prev, x });
			prev = x;
		}
		sections.add(new double[] { prev, stack1 });
		Map<Integer, List<Model3d.Part>> bySection = new HashMap<>();
		Map<Integer, List<Model3d.Part>> byStage = new HashMap<>();
		for (Model3d.Part p : parts) {
			if (trackStage.containsValue(p.stage)) {
				byStage.computeIfAbsent(p.stage, x -> new ArrayList<>()).add(p);
				continue;
			}
			double x = pieceStart.getOrDefault(p.piece, p.x0) + 1e-4;
			int s = 0;
			while (s < joints.size() && x >= joints.get(s)) {
				s++;
			}
			bySection.computeIfAbsent(s, x2 -> new ArrayList<>()).add(p);
		}
		bySection.forEach((s, ps) -> {
			List<Raster3d.Mesh> ms = new ArrayList<>();
			for (Model3d.Part p : ps) {
				ms.add(new Raster3d.Mesh(p.tris, p.rgb()));
			}
			sectionMeshes.put(s, ms);
		});
		byStage.forEach((s, ps) -> {
			Raster3d.Mesh[] ms = new Raster3d.Mesh[ps.size()];
			double a = Double.MAX_VALUE, b = 0;
			for (int i = 0; i < ps.size(); i++) {
				ms[i] = new Raster3d.Mesh(ps.get(i).tris, ps.get(i).rgb());
				a = Math.min(a, ps.get(i).x0);
				b = Math.max(b, ps.get(i).x1);
			}
			stageMeshes.put(s, ms);
			stagePivot.put(s, new double[] { (a + b) / 2 });
		});

		axis0 = m.axis(0);
		double cg = m.at(m.cg, 0);
		cg0 = Double.isNaN(cg) ? length / 2 : cg;
		double aft = 0;
		for (Model3d.Part p : parts) {
			aft = Math.max(aft, p.x1); // the aft end of the whole vehicle, boosters included
		}
		lift = 0.25 + (aft - cg0) * Math.max(0, axis0[2]);
		double[] land = m.position(tEnd), top = m.position(apogee);
		double[] d2 = Math.hypot(land[0], land[1]) > 5 ? land : Math.hypot(top[0], top[1]) > 2 ? top : new double[] { 1, 0, 0 };
		double dl = Math.hypot(d2[0], d2[1]);
		drift = new double[] { d2[0] / dl, d2[1] / dl, 0 };
		side = new double[] { -drift[1], drift[0], 0 };
		pad = new Raster3d.Mesh(box(new double[] { -1, -1, 0 }, new double[] { 1, 1, 0.12 }), 0xa9a79f);
		rail = new Raster3d.Mesh(railMesh(), 0x6c717a);
		flameOuter = new Raster3d.Mesh(cone(1, 1, 16), 0xff8a1f, true);
		flameInner = new Raster3d.Mesh(cone(0.6, 0.55, 16), 0xfff0a0, true);
		int[][] gores = { { 0xff7a1a, 0x1f2a3c }, { 0xd7263d, 0xf4f4f4 }, { 0x1f6feb, 0xf4f4f4 }, { 0x2e9e5b, 0xf4f4f4 } };
		canopies = new Raster3d.Mesh[deploys.size()][];
		for (int i = 0; i < deploys.size(); i++) {
			List<double[]>[] dome = dome(12);
			int[] c = gores[i % gores.length];
			canopies[i] = new Raster3d.Mesh[] { new Raster3d.Mesh(dome[0], c[0]), new Raster3d.Mesh(dome[1], c[1]) };
		}
		for (double t = 0; t <= f.burnout() + 1e-9 && t <= tEnd; t += 0.04) {
			double[] p = m.position(t);
			smoke.add(new double[] { t, p[0], p[1], p[2] + lift });
		}
	}

	// ------------------------------------------------------------------------------------------- schedule

	/** Flight time shown in frame {@code i} (negative before liftoff; the landing time during the final hold). */
	public double timeOf(int i) {
		double a = i / o.fps();
		if (a < PRE) {
			return a - PRE;
		}
		a -= PRE;
		if (a >= animEnd) {
			return gridT[gridT.length - 1];
		}
		int lo = 0, hi = gridA.length - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (gridA[mid] <= a) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double u = (a - gridA[lo]) / Math.max(1e-12, gridA[hi] - gridA[lo]);
		return gridT[lo] + u * (gridT[hi] - gridT[lo]);
	}

	/** Animation time (s) at which the flight reaches time t. */
	public double animOf(double t) {
		if (t <= 0) {
			return PRE + t;
		}
		int i = Math.min(gridT.length - 1, (int) Math.round(t / 0.01));
		return PRE + gridA[i];
	}

	/** Playback rate (x real time) at flight time t. */
	double rate(double t) {
		if (t <= 0) {
			return 1;
		}
		int i = Math.min(gridT.length - 1, (int) Math.round(t / 0.01));
		return 1 / gridD[i];
	}

	public double fps() {
		return o.fps();
	}

	public double seconds() {
		return frames / o.fps();
	}

	// ------------------------------------------------------------------------------------------- frames

	/** Renders frame {@code i} (independent of every other frame, so frames can render in parallel). */
	public BufferedImage frame(int i) {
		double a = i / o.fps();
		double t = timeOf(i);
		boolean hold = a > PRE + animEnd;
		return render(t, hold ? a - PRE - animEnd : 0);
	}

	/**
	 * How many frames to render at once: one per core, but no more than a third of the free heap allows (each frame
	 * needs about 40 bytes per pixel for its supersampled colour and depth buffers).
	 */
	public int parallelism() {
		Runtime rt = Runtime.getRuntime();
		long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
		long perFrame = 40L * w * h;
		return (int) Math.max(1, Math.min(rt.availableProcessors(), free / 3 / perFrame));
	}

	/** Frames {@code from} (inclusive) to {@code to} (exclusive), rendered in parallel, in order. */
	public List<BufferedImage> frames(int from, int to) {
		return IntStream.range(from, to).parallel().mapToObj(this::frame).toList();
	}

	BufferedImage render(double t, double holdTime) {
		Track m = f.main();
		double tc = Math.max(0, t);
		double tDep = deploys.isEmpty() ? Double.NaN : deploys.get(0).time();
		double kHang = Double.isNaN(tDep) ? 0 : smooth((tc - tDep) / 1.2);
		double[] pos = m.position(tc);
		pos[2] += lift;
		// Hanging system: how tall it is and where the harness is.
		double maxSec = 0;
		for (double[] s : sections) {
			maxSec = Math.max(maxSec, s[1] - s[0]);
		}
		double canopyD = 0;
		for (Event e : deploys) {
			if (e.time() <= tc) {
				canopyD = Math.max(canopyD, e.size());
			}
		}
		double hangH = 0.95 * maxSec + maxR + 0.3;
		double sysH = hangH + 0.4 * length + 1.4 * canopyD;
		double size = length + kHang * (sysH - length);
		double[] target = add(new double[] { m.position(tc)[0], m.position(tc)[1], m.position(tc)[2] + lift * (1 - kHang) },
				new double[] { 0, 0, kHang * sysH * 0.55 });
		double el = Math.toRadians(-9 + 22 * smooth((tc - f.apogeeTime() + 1) / 3));
		double dist = 2.5 * size * (1 + 0.9 * Math.max(0, 1 - tc / 1.5));
		double[] dir = Raster3d.norm(new double[] { side[0] * 0.92 - drift[0] * 0.38, side[1] * 0.92 - drift[1] * 0.38, 0 });
		double[] eye = { target[0] + dist * Math.cos(el) * dir[0], target[1] + dist * Math.cos(el) * dir[1],
				target[2] + dist * Math.sin(el) };
		eye[2] = Math.max(eye[2], 2.2);
		Raster3d.Camera cam = Raster3d.Camera.lookAt(eye, target, FOV);
		// Sun behind the camera, above and to the left, so the side we see is lit.
		double[] sun = Raster3d.norm(new double[] { -cam.fwd[0] - 0.5 * cam.right[0], -cam.fwd[1] - 0.5 * cam.right[1], 0.9 });
		Raster3d r = new Raster3d(w, h, 2, cam, sun);
		r.background(background(cam, r.focal()));

		// Behind everything: smoke, the flown path, shroud lines and cords.
		Graphics2D cg = r.canvas().createGraphics();
		cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		drawSmoke(cg, r, tc);
		// Rocket and pad.
		r.draw(Raster3d.Placed.at(pad, 0, 0, 0));
		r.draw(new Raster3d.Placed(rail, Raster3d.Placed.IDENTITY, new double[] { 0, 0, 0 }));
		double roll = m.at(m.roll, tc) * (1 - kHang);
		double[] ax = t < 0 ? axis0 : m.axis(tc);
		double cgx = m.at(m.cg, tc);
		cgx = Double.isNaN(cgx) ? cg0 : cgx;
		double[] rot = Raster3d.alongAxis(neg(ax), roll);
		double[] flightOrigin = sub(pos, mul(rot, new double[] { cgx, 0, 0 }));
		double[] harness = add(m.position(tc), new double[] { 0, 0, hangH });
		int n = sections.size();
		for (int s = 0; s < n; s++) {
			List<Raster3d.Mesh> ms = sectionMeshes.get(s);
			if (ms == null) {
				continue;
			}
			double[] rs = rot, os = flightOrigin;
			if (kHang > 0) {
				double attachX;
				double[] hangTail;
				if (n == 1) {
					attachX = cgx;
					hangTail = drift;
				} else if (s == 0) {
					attachX = sections.get(0)[1];
					hangTail = Raster3d.norm(new double[] { 0.5 * drift[0], 0.5 * drift[1], 0.87 });
				} else {
					attachX = sections.get(s)[0];
					double phi = Math.toRadians(50 + 18 * (s - 1));
					hangTail = Raster3d.norm(new double[] { Math.cos(phi) * drift[0], Math.cos(phi) * drift[1], -Math.sin(phi) });
				}
				double off = n == 1 ? 0 : (s - (n - 1) / 2.0) * 2.5 * maxR;
				double[] hangAttach = add(harness, new double[] { drift[0] * off, drift[1] * off, 0 });
				double[] flightAttach = add(flightOrigin, mul(rot, new double[] { attachX, 0, 0 }));
				double[] tail = Raster3d.norm(lerp(neg(ax), hangTail, kHang));
				rs = Raster3d.alongAxis(tail, roll);
				double[] at = lerp(flightAttach, hangAttach, kHang);
				os = sub(at, mul(rs, new double[] { attachX, 0, 0 }));
				if (kHang > 0.3) {
					line(cg, r, at, harness, new Color(0xe8d9b5), 0.012);
				}
			}
			for (Raster3d.Mesh mesh : ms) {
				r.draw(new Raster3d.Placed(mesh, rs, os));
			}
		}
		// Stages that drop off: on the vehicle until they separate, then on their own track.
		for (Map.Entry<Integer, Integer> e : trackStage.entrySet()) {
			Raster3d.Mesh[] stage = stageMeshes.get(e.getValue());
			if (stage == null) {
				continue;
			}
			Track b = f.tracks().get(e.getKey());
			double[] br = rot, bo = flightOrigin;
			if (tc >= b.separation) {
				double[] bp = b.position(tc);
				bp[2] += maxR;
				br = Raster3d.alongAxis(neg(b.axis(tc)), b.at(b.roll, tc));
				bo = sub(bp, mul(br, new double[] { stagePivot.get(e.getValue())[0], 0, 0 }));
			}
			for (Raster3d.Mesh mesh : stage) {
				r.draw(new Raster3d.Placed(mesh, br, bo));
			}
		}
		// Exhaust flame while the motor is burning.
		double thrust = t < 0 ? 0 : m.at(m.thrust, tc);
		if (thrust > 0.01 * f.maxThrust() && kHang == 0) {
			double flick = 1 + 0.08 * Math.sin(tc * 97.0) + 0.05 * Math.sin(tc * 41.0);
			for (double[] nz : burning(tc)) {
				double fl = nz[3] * 2 * (3 + 7 * thrust / Math.max(1, f.maxThrust())) * flick;
				double[] at = add(flightOrigin, mul(rot, new double[] { nz[0], nz[1], nz[2] }));
				r.draw(new Raster3d.Placed(flameOuter, scaled(rot, fl, nz[3] * 1.1, nz[3] * 1.1), at));
				r.draw(new Raster3d.Placed(flameInner, scaled(rot, fl, nz[3] * 1.1, nz[3] * 1.1), at));
			}
		}
		// Canopies: the newest above the harness, earlier ones (a drogue that stays attached) beside it.
		double beside = 0;
		for (int i = deploys.size() - 1; i >= 0; i--) {
			Event e = deploys.get(i);
			if (e.time() > tc || e.size() <= 0) {
				continue;
			}
			double inflate = Math.max(0.06, smooth((tc - e.time() - 0.1) / 0.9));
			if (holdTime > 0) {
				inflate *= Math.max(0.15, 1 - holdTime / 1.5); // collapses on the ground
			}
			double d = e.size() * inflate;
			double sway = 0.12 * Math.sin(0.7 * tc + i);
			double[] up = Raster3d.norm(new double[] { -drift[0] * sway, -drift[1] * sway, 1 });
			double lines = e.streamer() ? 0 : 0.9 * e.size();
			double off = beside == 0 ? 0 : beside + 0.6 * e.size();
			double[] base = { harness[0] + drift[0] * (sway * lines + off), harness[1] + drift[1] * (sway * lines + off),
					harness[2] + 0.4 * length + lines };
			if (e.streamer()) {
				double[] tip = add(base, new double[] { 0, 0, d });
				line(cg, r, harness, base, new Color(0xe8d9b5), 0.012);
				line(cg, r, base, tip, new Color(0xff7a1a), Math.max(0.03, e.size() * 0.08));
			} else {
				double[] cr = scaled(Raster3d.alongAxis(up, 0), d, d, d);
				double[] conf = sub(base, new double[] { 0, 0, lines });
				for (int g = 0; g < 8; g++) {
					double th = 2 * Math.PI * g / 8;
					double[] rim = add(base, mul(cr, new double[] { 0, 0.5 * Math.cos(th), 0.5 * Math.sin(th) }));
					line(cg, r, rim, conf, new Color(0xdcdcdc), 0.004);
				}
				line(cg, r, conf, harness, new Color(0xe8d9b5), 0.012);
				for (Raster3d.Mesh mesh : canopies[i]) {
					r.draw(new Raster3d.Placed(mesh, cr, base));
				}
			}
			beside = off + 0.6 * Math.max(d, 0.3);
		}
		cg.dispose();
		BufferedImage img = r.image();
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		hud(g, t, holdTime > 0);
		banners(g, t, holdTime);
		trajectoryInset(g, tc);
		altitudeInset(g, tc);
		timeline(g, t, holdTime);
		if (holdTime > 0.6) {
			summary(g, Math.min(1, (holdTime - 0.6) / 0.6));
		}
		g.dispose();
		return img;
	}

	/**
	 * Nozzles that burn while the vehicle has thrust at time t: those of the lowest stage still attached (the booster,
	 * then the sustainer once the booster drops) and of any side boosters still attached.
	 */
	private List<double[]> burning(double t) {
		int lowest = -1;
		List<double[]> out = new ArrayList<>();
		for (int st : nozzles.keySet()) {
			if (!dropped(st, t) && !sideBoosters.contains(st)) {
				lowest = Math.max(lowest, st);
			}
		}
		for (Map.Entry<Integer, List<double[]>> e : nozzles.entrySet()) {
			int st = e.getKey();
			if (!dropped(st, t) && (st == lowest || sideBoosters.contains(st))) {
				out.addAll(e.getValue());
			}
		}
		return out;
	}

	private boolean dropped(int stage, double t) {
		for (Map.Entry<Integer, Integer> e : trackStage.entrySet()) {
			if (e.getValue() == stage && t >= f.tracks().get(e.getKey()).separation) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------------------------------- world

	/** Sky and ground for this camera: a patchwork of fields with fog toward the horizon, the pad area in gravel. */
	int[] background(Raster3d.Camera cam, double focal) {
		int[] px = new int[w * h];
		double camAlt = cam.eye[2];
		double dark = Math.min(1, camAlt / 9000);
		int[] zenith = mix(new int[] { 74, 134, 204 }, new int[] { 22, 52, 112 }, dark);
		int[] horizon = mix(new int[] { 203, 222, 240 }, new int[] { 170, 196, 226 }, dark);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double u = (x + 0.5 - w / 2.0) / focal, v = (h / 2.0 - y - 0.5) / focal;
				double dx = cam.fwd[0] + u * cam.right[0] + v * cam.up[0];
				double dy = cam.fwd[1] + u * cam.right[1] + v * cam.up[1];
				double dz = cam.fwd[2] + u * cam.right[2] + v * cam.up[2];
				double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
				dz /= l;
				int c;
				if (dz >= -1e-4) {
					double s = Math.pow(Math.min(1, dz * 1.6), 0.6);
					c = pack(mix(horizon, zenith, s));
				} else {
					double s = camAlt / -dz; // distance along the ray to the ground
					double gx = cam.eye[0] + s * dx / l, gy = cam.eye[1] + s * dy / l;
					int[] ground = field(gx, gy);
					double pr = Math.hypot(gx, gy);
					if (pr < 25) {
						ground = mix(new int[] { 176, 168, 150 }, ground, smooth((pr - 18) / 7));
					}
					double fog = 1 - Math.exp(-s / 9000);
					c = pack(mix(ground, horizon, fog));
				}
				px[y * w + x] = c;
			}
		}
		return px;
	}

	/** Farm fields 150 m on a side, each a slightly different green or tan. */
	static int[] field(double x, double y) {
		long ix = (long) Math.floor(x / 150), iy = (long) Math.floor(y / 150);
		long hsh = ix * 73856093L ^ iy * 19349663L;
		hsh = (hsh ^ (hsh >>> 13)) * 0x5bd1e995L;
		double v = ((hsh >>> 8) & 1023) / 1023.0;
		int[] a = { 120, 150, 88 }, b = { 164, 158, 104 }, c = { 96, 128, 76 };
		int[] col = v < 0.5 ? mix(a, c, v * 2) : mix(a, b, (v - 0.5) * 2);
		// Darker field edges (hedgerows / tracks) give a sense of scale and speed.
		double ex = Math.abs(x / 150 - Math.rint(x / 150)) * 150, ey = Math.abs(y / 150 - Math.rint(y / 150)) * 150;
		if (Math.min(ex, ey) < 1.5) {
			col = mix(col, new int[] { 86, 98, 64 }, 0.6);
		}
		return col;
	}

	private void drawSmoke(Graphics2D g, Raster3d r, double t) {
		double diam = 0.05;
		for (double[] nz : burning(0)) {
			diam = Math.max(diam, 4.4 * nz[3]);
		}
		double[] prev = null;
		for (double[] s : smoke) {
			if (s[0] > t) {
				break;
			}
			double age = t - s[0];
			float alpha = (float) (0.8 - age * 0.02); // spreads and thins out over about 40 s
			double[] p = alpha > 0.02 ? r.projectSs(new double[] { s[1], s[2], s[3] }) : null;
			if (p != null && p[2] < 0.5) {
				p = null; // at the camera: skip rather than cover the frame
			}
			if (p != null && prev != null) {
				double width = (diam + age * 0.6) * r.focal() * 2 / p[2];
				g.setColor(new Color(1f, 1f, 1f, alpha * 0.85f));
				g.setStroke(new BasicStroke((float) Math.max(1, Math.min(width, 4000)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawLine((int) prev[0], (int) prev[1], (int) p[0], (int) p[1]);
			}
			prev = p;
		}
	}

	private static void line(Graphics2D g, Raster3d r, double[] a, double[] b, Color c, double widthM) {
		double[] p = r.projectSs(a), q = r.projectSs(b);
		if (p == null || q == null || p[2] < 0.3 || q[2] < 0.3) {
			return;
		}
		double px = Math.max(1.2, widthM * r.focal() * 2 / Math.max(1e-3, (p[2] + q[2]) / 2));
		g.setColor(c);
		g.setStroke(new BasicStroke((float) Math.min(px, 40), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine((int) p[0], (int) p[1], (int) q[0], (int) q[1]);
	}

	private List<double[]> railMesh() {
		List<double[]> out = new ArrayList<>();
		double[] rot = Raster3d.alongAxis(axis0, 0);
		double s = 0.02;
		for (double[] t : box(new double[] { 0, -s, -s }, new double[] { Math.max(0.5, railLength), s, s })) {
			double[] q = new double[9];
			for (int v = 0; v < 3; v++) {
				double[] p = mul(rot, new double[] { t[3 * v], t[3 * v + 1], t[3 * v + 2] });
				// Beside the rocket (one body radius off its axis, away from the camera), standing on the pad.
				q[3 * v] = p[0] - side[0] * (maxR + s);
				q[3 * v + 1] = p[1] - side[1] * (maxR + s);
				q[3 * v + 2] = p[2] + 0.12;
			}
			out.add(q);
		}
		return out;
	}

	static List<double[]> box(double[] lo, double[] hi) {
		double[][] c = new double[8][];
		for (int i = 0; i < 8; i++) {
			c[i] = new double[] { (i & 1) == 0 ? lo[0] : hi[0], (i & 2) == 0 ? lo[1] : hi[1], (i & 4) == 0 ? lo[2] : hi[2] };
		}
		int[][] faces = { { 0, 2, 3, 1 }, { 4, 5, 7, 6 }, { 0, 1, 5, 4 }, { 2, 6, 7, 3 }, { 0, 4, 6, 2 }, { 1, 3, 7, 5 } };
		List<double[]> out = new ArrayList<>();
		for (int[] fc : faces) {
			out.add(tri(c[fc[0]], c[fc[1]], c[fc[2]]));
			out.add(tri(c[fc[0]], c[fc[2]], c[fc[3]]));
		}
		return out;
	}

	/** A cone along +x from its base (radius r at x = 0) to its tip at x = len. */
	static List<double[]> cone(double len, double r, int seg) {
		List<double[]> out = new ArrayList<>();
		for (int j = 0; j < seg; j++) {
			double a0 = 2 * Math.PI * j / seg, a1 = 2 * Math.PI * (j + 1) / seg;
			double[] p0 = { 0, r * Math.cos(a0), r * Math.sin(a0) }, p1 = { 0, r * Math.cos(a1), r * Math.sin(a1) };
			out.add(tri(p0, p1, new double[] { len, 0, 0 }));
			out.add(tri(p0, new double[] { 0, 0, 0 }, p1));
		}
		return out;
	}

	/** A canopy of unit diameter, apex along +x, as two sets of gores in alternating colours. */
	@SuppressWarnings("unchecked")
	static List<double[]>[] dome(int gores) {
		List<double[]>[] out = new List[] { new ArrayList<>(), new ArrayList<>() };
		int rings = 8, per = 4;
		for (int gI = 0; gI < gores; gI++) {
			for (int s = 0; s < per; s++) {
				double a0 = 2 * Math.PI * (gI * per + s) / (gores * per), a1 = 2 * Math.PI * (gI * per + s + 1) / (gores * per);
				for (int i = 0; i < rings; i++) {
					double p0 = Math.PI / 2 * i / rings, p1 = Math.PI / 2 * (i + 1) / rings;
					double[] q00 = domePt(p0, a0), q01 = domePt(p0, a1), q10 = domePt(p1, a0), q11 = domePt(p1, a1);
					out[gI % 2].add(tri(q00, q10, q11));
					out[gI % 2].add(tri(q00, q11, q01));
				}
			}
		}
		return out;
	}

	/** A point of a slightly flattened hemisphere (polar angle p from the apex, azimuth a), rim at x = 0. */
	private static double[] domePt(double p, double a) {
		double rr = 0.5 * Math.sin(p);
		return new double[] { 0.42 * Math.cos(p), rr * Math.cos(a), rr * Math.sin(a) };
	}

	// ------------------------------------------------------------------------------------------- overlays

	private Font font(int style, double size) {
		return new Font(Font.SANS_SERIF, style, (int) Math.round(size * k));
	}

	private void panel(Graphics2D g, double x, double y, double pw, double ph) {
		g.setColor(new Color(12, 18, 28, 165));
		g.fill(new RoundRectangle2D.Double(x, y, pw, ph, 14 * k, 14 * k));
	}

	/** {value, unit} in the team's primary units; {@code alt} gives the other system for "both". */
	static String[] value(double si, Dim dim, boolean alt) {
		UnitSystem sys = Units.system();
		boolean imperial = sys == UnitSystem.IMPERIAL ? !alt : alt && sys == UnitSystem.BOTH;
		String unit = imperial ? dim.imperial : dim.metric;
		double v = Units.fromSi(si, unit);
		String s = Math.abs(v) >= 100 ? String.format(Locale.ROOT, "%,.0f", v) : String.format(Locale.ROOT, "%.1f", v);
		return new String[] { s, unit };
	}

	private void hud(Graphics2D g, double t, boolean hold) {
		Track m = f.main();
		double tc = Math.max(0, t);
		double x0 = 18 * k, y0 = 18 * k, pw = 300 * k, row = 34 * k;
		String[][] rows = new String[7][];
		double[] p = m.position(tc);
		boolean both = Units.system() == UnitSystem.BOTH;
		rows[0] = new String[] { "ALTITUDE", fmt(value(p[2], Dim.DISTANCE, false)), both ? fmt(value(p[2], Dim.DISTANCE, true)) : "" };
		double v = t < 0 ? 0 : m.at(m.speed, tc), vz = t < 0 ? 0 : m.at(m.vz, tc);
		rows[1] = new String[] { "VELOCITY", fmt(value(v, Dim.VELOCITY, false)), both ? fmt(value(v, Dim.VELOCITY, true)) : "" };
		rows[2] = new String[] { "VERTICAL", (vz >= 0 ? "+" : "") + fmt(value(vz, Dim.VELOCITY, false)), "" };
		double mach = t < 0 ? 0 : m.at(m.mach, tc);
		rows[3] = new String[] { "MACH", Double.isNaN(mach) ? "-" : String.format(Locale.ROOT, "%.2f", mach), "" };
		double acc = t < 0 ? 0 : m.at(m.acc, tc) / 9.80665;
		rows[4] = new String[] { "ACCEL", Double.isNaN(acc) ? "-" : String.format(Locale.ROOT, "%.1f g", acc), "" };
		double dist = Math.hypot(p[0], p[1]);
		rows[5] = new String[] { "FROM PAD", fmt(value(dist, Dim.DISTANCE, false)), both ? fmt(value(dist, Dim.DISTANCE, true)) : "" };
		double ws = m.at(m.windSpeed, tc);
		rows[6] = new String[] { "WIND", Double.isNaN(ws) ? "-" : fmt(value(ws, Dim.VELOCITY, false)), "" };
		double ph = 64 * k + rows.length * row + 12 * k;
		panel(g, x0, y0, pw, ph);
		g.setColor(Color.WHITE);
		g.setFont(font(Font.BOLD, 30));
		String clock = (t < 0 ? "T−" : "T+") + clock(Math.abs(t));
		g.drawString(clock, (float) (x0 + 16 * k), (float) (y0 + 42 * k));
		g.setFont(font(Font.PLAIN, 13));
		g.setColor(new Color(0xb9c6d8));
		String rate = hold ? "" : rateLabel(t);
		g.drawString(rate, (float) (x0 + pw - 16 * k - g.getFontMetrics().stringWidth(rate)), (float) (y0 + 40 * k));
		for (int i = 0; i < rows.length; i++) {
			double y = y0 + 64 * k + i * row + 22 * k;
			g.setFont(font(Font.PLAIN, 13));
			g.setColor(new Color(0x9fb0c8));
			g.drawString(rows[i][0], (float) (x0 + 16 * k), (float) y);
			g.setFont(font(Font.BOLD, 20));
			g.setColor(Color.WHITE);
			FontMetrics fm = g.getFontMetrics();
			double right = x0 + pw - 16 * k;
			if (!rows[i][2].isEmpty()) {
				g.setFont(font(Font.PLAIN, 12));
				g.setColor(new Color(0x9fb0c8));
				String alt = rows[i][2];
				g.drawString(alt, (float) (right - g.getFontMetrics().stringWidth(alt)), (float) y);
				right -= g.getFontMetrics().stringWidth(alt) + 10 * k;
				g.setFont(font(Font.BOLD, 20));
				g.setColor(Color.WHITE);
				fm = g.getFontMetrics();
			}
			g.drawString(rows[i][1], (float) (right - fm.stringWidth(rows[i][1])), (float) y);
		}
		// Flight phase under the panel.
		String phase = phase(t);
		g.setFont(font(Font.BOLD, 14));
		FontMetrics fm = g.getFontMetrics();
		double cw = fm.stringWidth(phase) + 24 * k;
		g.setColor(phaseColour(t));
		g.fill(new RoundRectangle2D.Double(x0, y0 + ph + 8 * k, cw, 28 * k, 28 * k, 28 * k));
		g.setColor(Color.WHITE);
		g.drawString(phase, (float) (x0 + 12 * k), (float) (y0 + ph + 8 * k + 19 * k));
	}

	private static String fmt(String[] v) {
		return v[0] + " " + v[1];
	}

	static String clock(double s) {
		int mm = (int) (s / 60);
		return String.format(Locale.ROOT, "%02d:%04.1f", mm, s - 60 * mm);
	}

	private String rateLabel(double t) {
		double r = rate(t);
		return r < 1.05 ? "real time" : String.format(Locale.ROOT, r < 10 ? "%.1f\u00d7 speed" : "%.0f\u00d7 speed", r);
	}

	String phase(double t) {
		if (t < 0) {
			return "ON THE PAD";
		}
		if (t >= f.landingTime() - 1e-6) {
			return "LANDED";
		}
		Event last = null;
		for (Event e : deploys) {
			if (e.time() <= t) {
				last = e;
			}
		}
		if (last != null) {
			return "UNDER " + last.device().toUpperCase(Locale.ROOT);
		}
		if (t < f.burnout()) {
			return "POWERED ASCENT";
		}
		return t < f.apogeeTime() ? "COASTING" : "FREE FALL";
	}

	private Color phaseColour(double t) {
		String p = phase(t);
		return p.startsWith("POWERED") ? new Color(0xd9480f) : p.startsWith("COAST") ? new Color(0x1c7ed6)
				: p.startsWith("UNDER") ? new Color(0x2b8a3e) : p.equals("LANDED") ? new Color(0x5f3dc4) : new Color(0x495057);
	}

	private void banners(Graphics2D g, double t, double hold) {
		double a = hold > 0 ? PRE + animEnd + hold : (t < 0 ? PRE + t : animOf(t));
		List<Event> show = new ArrayList<>();
		for (Event e : f.events()) {
			double ae = animOf(e.time());
			if (a >= ae && a < ae + 2.6) {
				show.add(e);
			}
		}
		while (show.size() > 2) {
			show.remove(0); // the newest two
		}
		if (t < 0 && t > -0.9) {
			show.add(new Event(0, "ignition", "Ignition", "", null, 0, false));
		}
		double y = 22 * k;
		for (Event e : show) {
			double ae = e.kind().equals("ignition") && t < 0 ? PRE - 0.9 : animOf(e.time());
			float alpha = (float) Math.min(1, Math.min((a - ae) / 0.25, (ae + 2.6 - a) / 0.5));
			alpha = Math.max(0, Math.min(1, alpha));
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			g.setFont(font(Font.BOLD, 28));
			FontMetrics fm = g.getFontMetrics();
			String title = e.title().toUpperCase(Locale.ROOT);
			g.setFont(font(Font.PLAIN, 15));
			FontMetrics fs = g.getFontMetrics();
			String sub = "T+" + String.format(Locale.ROOT, "%.1f s", e.time()) + (e.detail().isEmpty() ? "" : "  ·  " + e.detail());
			double bw = Math.max(fm.stringWidth(title), fs.stringWidth(sub)) + 40 * k;
			double bx = (w - bw) / 2;
			g.setColor(new Color(12, 18, 28, 190));
			g.fill(new RoundRectangle2D.Double(bx, y, bw, 70 * k, 16 * k, 16 * k));
			g.setColor(new Color(0xffd43b));
			g.fill(new RoundRectangle2D.Double(bx, y, 6 * k, 70 * k, 6 * k, 6 * k));
			g.setColor(Color.WHITE);
			g.setFont(font(Font.BOLD, 28));
			g.drawString(title, (float) ((w - fm.stringWidth(title)) / 2.0), (float) (y + 34 * k));
			g.setFont(font(Font.PLAIN, 15));
			g.setColor(new Color(0xd0d8e4));
			g.drawString(sub, (float) ((w - fs.stringWidth(sub)) / 2.0), (float) (y + 58 * k));
			g.setComposite(AlphaComposite.SrcOver);
			y += 78 * k;
		}
	}

	/** The whole trajectory in a small 3-D view: flown part coloured by phase, the pad, the landing point. */
	private void trajectoryInset(Graphics2D g, double t) {
		double iw = 300 * k, ih = 210 * k, x0 = w - iw - 18 * k, y0 = 18 * k;
		panel(g, x0, y0, iw, ih);
		Track m = f.main();
		double az = Math.toRadians(35), el = Math.toRadians(22);
		double[] ex = { drift[0] * Math.cos(az) + side[0] * Math.sin(az), drift[1] * Math.cos(az) + side[1] * Math.sin(az) };
		double[] ed = { -drift[0] * Math.sin(az) + side[0] * Math.cos(az), -drift[1] * Math.sin(az) + side[1] * Math.cos(az) };
		int n = 160;
		double[][] pts = new double[n + 1][];
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		double tEnd = m.end();
		for (int i = 0; i <= n; i++) {
			double ti = tEnd * i / n;
			double[] p = m.position(ti);
			pts[i] = proj(p, ex, ed, el);
		}
		double[][] ground = new double[4][];
		double gx0 = 0, gx1 = 0, gy0 = 0, gy1 = 0;
		for (int i = 0; i <= n; i++) {
			double[] p = m.position(tEnd * i / n);
			gx0 = Math.min(gx0, p[0]);
			gx1 = Math.max(gx1, p[0]);
			gy0 = Math.min(gy0, p[1]);
			gy1 = Math.max(gy1, p[1]);
		}
		double gm = 0.15 * Math.max(1, Math.max(gx1 - gx0, gy1 - gy0)) + 50;
		double[][] corners = { { gx0 - gm, gy0 - gm, 0 }, { gx1 + gm, gy0 - gm, 0 }, { gx1 + gm, gy1 + gm, 0 }, { gx0 - gm, gy1 + gm, 0 } };
		for (int i = 0; i < 4; i++) {
			ground[i] = proj(corners[i], ex, ed, el);
		}
		for (double[] q : concat(pts, ground)) {
			minX = Math.min(minX, q[0]);
			maxX = Math.max(maxX, q[0]);
			minY = Math.min(minY, q[1]);
			maxY = Math.max(maxY, q[1]);
		}
		double pad = 16 * k, top = 30 * k;
		double s = Math.min((iw - 2 * pad) / Math.max(1e-6, maxX - minX), (ih - pad - top) / Math.max(1e-6, maxY - minY));
		double ox = x0 + pad + ((iw - 2 * pad) - s * (maxX - minX)) / 2 - s * minX;
		double oy = y0 + top + ((ih - pad - top) - s * (maxY - minY)) / 2 + s * maxY;
		Path2D gp = new Path2D.Double();
		for (int i = 0; i < 4; i++) {
			double px = ox + s * ground[i][0], py = oy - s * ground[i][1];
			if (i == 0) {
				gp.moveTo(px, py);
			} else {
				gp.lineTo(px, py);
			}
		}
		gp.closePath();
		g.setColor(new Color(0x46603a));
		g.fill(gp);
		g.setColor(new Color(255, 255, 255, 60));
		g.setStroke(new BasicStroke((float) (1.2 * k)));
		Path2D all = new Path2D.Double();
		for (int i = 0; i <= n; i++) {
			double px = ox + s * pts[i][0], py = oy - s * pts[i][1];
			if (i == 0) {
				all.moveTo(px, py);
			} else {
				all.lineTo(px, py);
			}
		}
		g.draw(all);
		// Flown part, coloured by phase.
		g.setStroke(new BasicStroke((float) (2.4 * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		double step = Math.max(0.02, tEnd / 400);
		double[] prev = null;
		for (double ti = 0; ti <= t + 1e-9; ti += step) {
			double[] q = proj(m.position(Math.min(ti, t)), ex, ed, el);
			double[] sp = { ox + s * q[0], oy - s * q[1] };
			if (prev != null) {
				g.setColor(phaseColour(ti).brighter());
				g.drawLine((int) prev[0], (int) prev[1], (int) sp[0], (int) sp[1]);
			}
			prev = sp;
		}
		// Pad, landing point and the rocket now, with a drop line to the ground.
		double[] padP = proj(new double[] { 0, 0, 0 }, ex, ed, el);
		g.setColor(Color.WHITE);
		g.fillRect((int) (ox + s * padP[0] - 3 * k), (int) (oy - s * padP[1] - 3 * k), (int) (6 * k), (int) (6 * k));
		double[] landP = proj(m.position(tEnd), ex, ed, el);
		g.setColor(new Color(0xffd43b));
		double lx = ox + s * landP[0], ly = oy - s * landP[1];
		g.drawLine((int) (lx - 5 * k), (int) (ly - 5 * k), (int) (lx + 5 * k), (int) (ly + 5 * k));
		g.drawLine((int) (lx - 5 * k), (int) (ly + 5 * k), (int) (lx + 5 * k), (int) (ly - 5 * k));
		double[] now = m.position(t);
		double[] nq = proj(now, ex, ed, el), nq0 = proj(new double[] { now[0], now[1], 0 }, ex, ed, el);
		g.setColor(new Color(255, 255, 255, 140));
		g.setStroke(new BasicStroke((float) k, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 3f, 3f }, 0f));
		g.drawLine((int) (ox + s * nq[0]), (int) (oy - s * nq[1]), (int) (ox + s * nq0[0]), (int) (oy - s * nq0[1]));
		g.setColor(new Color(0xff6b6b));
		g.fillOval((int) (ox + s * nq[0] - 5 * k), (int) (oy - s * nq[1] - 5 * k), (int) (10 * k), (int) (10 * k));
		g.setColor(new Color(0xd0d8e4));
		g.setFont(font(Font.BOLD, 12));
		g.drawString("TRAJECTORY", (float) (x0 + 12 * k), (float) (y0 + 20 * k));
		g.setFont(font(Font.PLAIN, 11));
		String lab = "\u25a0 pad   \u00d7 landing";
		g.drawString(lab, (float) (x0 + iw - 12 * k - g.getFontMetrics().stringWidth(lab)), (float) (y0 + 20 * k));
	}

	private static double[] proj(double[] p, double[] ex, double[] ed, double el) {
		double x = p[0] * ex[0] + p[1] * ex[1];
		double depth = p[0] * ed[0] + p[1] * ed[1];
		return new double[] { x, p[2] * Math.cos(el) + depth * Math.sin(el) };
	}

	private static double[][] concat(double[][] a, double[][] b) {
		double[][] out = new double[a.length + b.length][];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/** Altitude against time with the events marked and the current point. */
	private void altitudeInset(Graphics2D g, double t) {
		double iw = 300 * k, ih = 130 * k, x0 = w - iw - 18 * k, y0 = 18 * k + 210 * k + 10 * k;
		panel(g, x0, y0, iw, ih);
		Track m = f.main();
		double tEnd = m.end(), top = 0;
		for (double a : m.alt) {
			if (!Double.isNaN(a)) {
				top = Math.max(top, a);
			}
		}
		top = Math.max(top, 1);
		double px0 = x0 + 14 * k, px1 = x0 + iw - 14 * k, py0 = y0 + 30 * k, py1 = y0 + ih - 14 * k;
		g.setStroke(new BasicStroke((float) (1.2 * k)));
		Path2D all = new Path2D.Double(), flown = new Path2D.Double();
		int n = 200;
		for (int i = 0; i <= n; i++) {
			double ti = tEnd * i / n;
			double x = px0 + (px1 - px0) * ti / tEnd, y = py1 - (py1 - py0) * Math.max(0, m.at(m.alt, ti)) / top;
			if (i == 0) {
				all.moveTo(x, y);
				flown.moveTo(x, y);
			} else {
				all.lineTo(x, y);
				if (ti <= t) {
					flown.lineTo(x, y);
				}
			}
		}
		double xt = px0 + (px1 - px0) * Math.min(t, tEnd) / tEnd, yt = py1 - (py1 - py0) * Math.max(0, m.at(m.alt, t)) / top;
		flown.lineTo(xt, yt);
		g.setColor(new Color(255, 255, 255, 60));
		g.draw(all);
		g.setColor(new Color(0x74c0fc));
		g.setStroke(new BasicStroke((float) (2.2 * k)));
		g.draw(flown);
		for (Event e : f.events()) {
			if (e.kind().equals("maxv") || e.kind().equals("rail")) {
				continue;
			}
			double x = px0 + (px1 - px0) * e.time() / tEnd, y = py1 - (py1 - py0) * Math.max(0, m.at(m.alt, e.time())) / top;
			g.setColor(e.time() <= t ? new Color(0xffd43b) : new Color(255, 255, 255, 90));
			g.fillOval((int) (x - 3 * k), (int) (y - 3 * k), (int) (6 * k), (int) (6 * k));
		}
		g.setColor(new Color(0xff6b6b));
		g.fillOval((int) (xt - 4.5 * k), (int) (yt - 4.5 * k), (int) (9 * k), (int) (9 * k));
		g.setColor(new Color(0xd0d8e4));
		g.setFont(font(Font.BOLD, 12));
		g.drawString("ALTITUDE", (float) (x0 + 12 * k), (float) (y0 + 20 * k));
		g.setFont(font(Font.PLAIN, 11));
		String lab = "max " + fmt(value(top, Dim.DISTANCE, false)) + "  ·  " + String.format(Locale.ROOT, "%.0f s", tEnd);
		g.drawString(lab, (float) (x0 + iw - 12 * k - g.getFontMetrics().stringWidth(lab)), (float) (y0 + 20 * k));
	}

	/** Event ticks along the animation's own timeline, with the playhead. */
	private void timeline(Graphics2D g, double t, double hold) {
		double total = PRE + animEnd + HOLD;
		double a = hold > 0 ? PRE + animEnd + hold : (t < 0 ? PRE + t : animOf(t));
		double x0 = 18 * k, x1 = w - 18 * k, y = h - 30 * k;
		g.setColor(new Color(12, 18, 28, 150));
		g.fill(new RoundRectangle2D.Double(x0 - 8 * k, y - 34 * k, x1 - x0 + 16 * k, 58 * k, 12 * k, 12 * k));
		g.setColor(new Color(255, 255, 255, 70));
		g.fill(new RoundRectangle2D.Double(x0, y - 2 * k, x1 - x0, 4 * k, 4 * k, 4 * k));
		g.setColor(new Color(0x74c0fc));
		g.fill(new RoundRectangle2D.Double(x0, y - 2 * k, (x1 - x0) * a / total, 4 * k, 4 * k, 4 * k));
		g.setFont(font(Font.PLAIN, 11));
		FontMetrics fm = g.getFontMetrics();
		double[] rowEnd = { -1e9, -1e9 }; // labels above the bar, then below it where they would overlap
		for (Event e : f.events()) {
			if (e.kind().equals("maxv")) {
				continue;
			}
			double x = x0 + (x1 - x0) * animOf(e.time()) / total;
			g.setColor(animOf(e.time()) <= a ? new Color(0xffd43b) : new Color(255, 255, 255, 130));
			g.fillRect((int) (x - k), (int) (y - 7 * k), (int) Math.max(2, 2 * k), (int) (14 * k));
			String lab = e.title();
			int lw = fm.stringWidth(lab);
			double lx = Math.max(x0, Math.min(x1 - lw, x - lw / 2.0));
			int row = lx < rowEnd[0] + 6 * k ? 1 : 0;
			if (row == 1 && lx < rowEnd[1] + 6 * k) {
				continue; // no room: the tick alone marks it
			}
			rowEnd[row] = lx + lw;
			g.drawString(lab, (float) lx, (float) (row == 0 ? y - 12 * k : y + 20 * k));
		}
		g.setColor(Color.WHITE);
		g.fillOval((int) (x0 + (x1 - x0) * a / total - 6 * k), (int) (y - 6 * k), (int) (12 * k), (int) (12 * k));
		// Title above the timeline, on the left.
		g.setFont(font(Font.BOLD, 14));
		g.setColor(Color.WHITE);
		g.drawString(o.title(), (float) x0, (float) (y - 44 * k));
		g.setFont(font(Font.PLAIN, 12));
		g.setColor(new Color(0xe0e6ee));
		g.drawString(o.subtitle(), (float) (x0 + g.getFontMetrics(font(Font.BOLD, 14)).stringWidth(o.title()) + 12 * k), (float) (y - 44 * k));
	}

	/** Flight summary shown over the final hold. */
	private void summary(Graphics2D g, double alpha) {
		Track m = f.main();
		double top = 0, vmax = 0, mmax = 0;
		for (int i = 0; i < m.t.length; i++) {
			top = Math.max(top, Double.isNaN(m.alt[i]) ? 0 : m.alt[i]);
			vmax = Math.max(vmax, Double.isNaN(m.speed[i]) ? 0 : m.speed[i]);
			mmax = Math.max(mmax, Double.isNaN(m.mach[i]) ? 0 : m.mach[i]);
		}
		double[] land = m.position(m.end());
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[] { "Apogee", Units.fmt(top, Dim.DISTANCE) + " at T+" + String.format(Locale.ROOT, "%.1f s", f.apogeeTime()) });
		rows.add(new String[] { "Max velocity", Units.fmt(vmax, Dim.VELOCITY) + String.format(Locale.ROOT, " (Mach %.2f)", mmax) });
		rows.add(new String[] { "Motor burnout", String.format(Locale.ROOT, "T+%.1f s", f.burnout()) });
		for (Event e : deploys) {
			rows.add(new String[] { e.title(), "T+" + String.format(Locale.ROOT, "%.1f s", e.time()) + ", " + e.detail() });
		}
		for (Event e : f.events()) {
			if (e.kind().equals("landing")) {
				rows.add(new String[] { "Touchdown", "T+" + String.format(Locale.ROOT, "%.1f s", e.time()) + ", " + e.detail() });
			}
		}
		if (rows.stream().noneMatch(r -> r[0].equals("Touchdown"))) {
			rows.add(new String[] { "Landing", Units.fmt(Math.hypot(land[0], land[1]), Dim.DISTANCE) + " from the pad" });
		}
		g.setFont(font(Font.BOLD, 15));
		double valueW = 0;
		for (String[] r : rows) {
			valueW = Math.max(valueW, g.getFontMetrics().stringWidth(r[1]));
		}
		double pw = Math.min(w - 40 * k, 224 * k + valueW), ph = 70 * k + rows.size() * 30 * k, x0 = (w - pw) / 2;
		double y0 = (h - ph) / 2 - 20 * k;
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
		g.setColor(new Color(12, 18, 28, 215));
		g.fill(new RoundRectangle2D.Double(x0, y0, pw, ph, 18 * k, 18 * k));
		g.setColor(Color.WHITE);
		g.setFont(font(Font.BOLD, 22));
		g.drawString("Flight summary", (float) (x0 + 24 * k), (float) (y0 + 40 * k));
		for (int i = 0; i < rows.size(); i++) {
			double y = y0 + 76 * k + i * 30 * k;
			g.setFont(font(Font.PLAIN, 15));
			g.setColor(new Color(0x9fb0c8));
			g.drawString(rows.get(i)[0], (float) (x0 + 24 * k), (float) y);
			g.setFont(font(Font.BOLD, 15));
			g.setColor(Color.WHITE);
			g.drawString(rows.get(i)[1], (float) (x0 + 200 * k), (float) y);
		}
		g.setComposite(AlphaComposite.SrcOver);
	}

	// ------------------------------------------------------------------------------------------- key frames

	/** Moments worth a still: liftoff, burnout, apogee, each deployment, just before touchdown. */
	public List<double[]> keyMoments() {
		List<double[]> out = new ArrayList<>(); // flight time, event index (-1 = none)
		List<Event> ev = f.events();
		for (int i = 0; i < ev.size(); i++) {
			Event e = ev.get(i);
			double t = switch (e.kind()) {
				case "liftoff" -> e.time() + 0.35;
				case "burnout", "apogee" -> e.time();
				case "deploy" -> e.time() + 1.8;
				case "landing" -> Math.max(0, e.time() - 1.0);
				default -> Double.NaN;
			};
			if (!Double.isNaN(t) && out.size() < 6) {
				out.add(new double[] { Math.min(t, f.main().end()), i });
			}
		}
		return out;
	}

	/** A sheet of stills at the key moments, captioned. */
	public BufferedImage contactSheet(int sheetWidth) {
		List<double[]> moments = keyMoments();
		int cols = Math.min(3, Math.max(1, moments.size())), rows = (int) Math.ceil(moments.size() / (double) cols);
		int gap = 12, tw = (sheetWidth - gap * (cols + 1)) / cols, th = tw * 9 / 16, cap = 46;
		BufferedImage sheet = new BufferedImage(sheetWidth, 56 + rows * (th + cap + gap) + gap, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setColor(new Color(0xf7f8fa));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		g.setColor(new Color(0x1a1a19));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
		g.drawString(o.title() + ": key moments", gap, 34);
		List<BufferedImage> stills = new ArrayList<>();
		for (int from = 0, par = parallelism(); from < moments.size(); from += par) {
			stills.addAll(IntStream.range(from, Math.min(moments.size(), from + par)).parallel()
					.mapToObj(i -> render(moments.get(i)[0], 0)).toList());
		}
		for (int i = 0; i < moments.size(); i++) {
			int x = gap + (i % cols) * (tw + gap), y = 56 + (i / cols) * (th + cap + gap);
			g.drawImage(stills.get(i), x, y, tw, th, null);
			Event e = f.events().get((int) moments.get(i)[1]);
			g.setColor(new Color(0x1a1a19));
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
			g.drawString(e.title(), x, y + th + 20);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
			g.setColor(new Color(0x5f5e57));
			g.drawString(String.format(Locale.ROOT, "T+%.1f s", moments.get(i)[0]) + (e.detail().isEmpty() ? "" : "  \u00b7  " + e.detail()),
					x, y + th + 38);
		}
		g.dispose();
		return sheet;
	}

	/** The captioned events with their flight and animation times. */
	public List<Map<String, Object>> timeline() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Event e : f.events()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("event", e.title());
			m.put("flightTime", Units.fmt(e.time(), Dim.TIME));
			m.put("videoTime", String.format(Locale.ROOT, "%.1f s", animOf(e.time())));
			if (!e.detail().isEmpty()) {
				m.put("detail", e.detail());
			}
			out.add(m);
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------- maths

	static double smooth(double x) {
		double c = Math.max(0, Math.min(1, x));
		return c * c * (3 - 2 * c);
	}

	private static int[] mix(int[] a, int[] b, double t) {
		return new int[] { (int) (a[0] + (b[0] - a[0]) * t), (int) (a[1] + (b[1] - a[1]) * t), (int) (a[2] + (b[2] - a[2]) * t) };
	}

	private static int pack(int[] c) {
		return c[0] << 16 | c[1] << 8 | c[2];
	}

	private static double[] add(double[] a, double[] b) {
		return Raster3d.add(a, b);
	}

	private static double[] sub(double[] a, double[] b) {
		return Raster3d.sub(a, b);
	}

	private static double[] neg(double[] a) {
		return new double[] { -a[0], -a[1], -a[2] };
	}

	private static double[] lerp(double[] a, double[] b, double t) {
		return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
	}

	/** Row-major 3 x 3 times a vector. */
	static double[] mul(double[] m, double[] v) {
		return new double[] { m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
				m[6] * v[0] + m[7] * v[1] + m[8] * v[2] };
	}

	/** Rotation times a diagonal scale (sx along local x, sy, sz). */
	static double[] scaled(double[] r, double sx, double sy, double sz) {
		return new double[] { r[0] * sx, r[1] * sy, r[2] * sz, r[3] * sx, r[4] * sy, r[5] * sz, r[6] * sx, r[7] * sy, r[8] * sz };
	}

	private static double[] tri(double[] a, double[] b, double[] c) {
		return new double[] { a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2] };
	}
}
