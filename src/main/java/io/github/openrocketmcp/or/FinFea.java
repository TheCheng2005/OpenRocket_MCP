package io.github.openrocketmcp.or;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.mcp.ToolException;

/**
 * Finite-element check of one fin with CalculiX (free, Abaqus-style input): the fin as a plate of 8-node shells
 * clamped along its root, under the aerodynamic load of the worst flight point, plus its natural modes.
 *
 * <p>Material: in-plane engineering constants (E, G, nu), so a woven laminate's low shear stiffness (the one flutter
 * depends on) is kept apart from its tensile stiffness; isotropic metals are the special case G = E / 2(1 + nu). The
 * deck is written in any case; when a CalculiX executable is available it is run and its results read back.
 */
public final class FinFea {
	private FinFea() {
	}

	/** Fin plate outline: root leading edge at (0, 0), root trailing edge (root, 0), tip (sweep, span)-(sweep + tip, span). */
	public record Plate(double root, double tip, double span, double sweep, double thickness, String note) {
		public double area() {
			return 0.5 * (root + tip) * span;
		}

		/** Spanwise position of the area centroid (moment arm of a uniform pressure about the root). */
		public double centroidSpan() {
			return span * (root + 2 * tip) / (3 * (root + tip));
		}
	}

	public static Plate plate(FinSet f, double thicknessOverride) {
		double t = Double.isNaN(thicknessOverride) ? f.getThickness() : thicknessOverride;
		if (f instanceof TrapezoidFinSet tf) {
			return new Plate(tf.getRootChord(), tf.getTipChord(), tf.getHeight(), tf.getSweep(), t, null);
		}
		// Other outlines as the quadrilateral through the root ends and the outermost points.
		Coordinate[] p = f.getFinPoints();
		double span = 0;
		for (Coordinate c : p) {
			span = Math.max(span, c.y);
		}
		double le = Double.MAX_VALUE, te = -Double.MAX_VALUE;
		for (Coordinate c : p) {
			if (c.y >= 0.98 * span) {
				le = Math.min(le, c.x);
				te = Math.max(te, c.x);
			}
		}
		double root = p[p.length - 1].x - p[0].x;
		double tip = Math.max(te - le, 0.05 * root);
		return new Plate(root, tip, span, le - p[0].x, t, "the " + f.getComponentName().toLowerCase(Locale.ROOT)
				+ " outline is modelled as the quadrilateral through its root and its outermost points (same span and root)");
	}

	/** Engineering constants (SI). */
	public record Material(double e, double g, double nu, double density, double strength, boolean metal) {
	}

	/** Design load: normal force on the most loaded fin and the flight point it comes from. */
	public record Load(double finForce, double q, double alpha, double mach, double time, String basis) {
	}

	/**
	 * The larger of (a) a crosswind gust at the flight's maximum dynamic pressure and (b) the largest simulated
	 * q x angle of attack; fin set normal force from OpenRocket's Barrowman fin model (fin-body interference included),
	 * on the most loaded fin (the ones square to the flow plane carry it: 2/n of the set for n >= 3).
	 */
	public static Load designLoad(Simulation sim, FinSet fin, double gust) {
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		FinSet f = (FinSet) Components.find(sim.getRocket(), fin.getID().toString());
		Branch br = Branch.of(sim.getSimulatedData().getBranch(0));
		double[] t = br.time, rho = br.col(FlightDataType.TYPE_AIR_DENSITY), mach = br.col(FlightDataType.TYPE_MACH_NUMBER),
				aoa = br.col(FlightDataType.TYPE_AOA);
		int iq = -1, ia = -1;
		double qMax = 0, qaMax = 0;
		for (int i = 0; i < t.length; i++) {
			double v = br.airspeed(i), q = 0.5 * rho[i] * v * v;
			if (Double.isNaN(q)) {
				continue;
			}
			if (q > qMax) {
				qMax = q;
				iq = i;
			}
			double qa = q * Math.abs(Double.isNaN(aoa[i]) ? 0 : aoa[i]);
			if (v > 30 && qa > qaMax) {
				qaMax = qa;
				ia = i;
			}
		}
		if (iq < 0) {
			throw new ToolException("The simulation has no flight data to take fin loads from.");
		}
		double alphaGust = Math.atan2(gust, br.airspeed(iq));
		double share = f.getFinCount() >= 3 ? f.getFinCount() / 2.0 : f.getFinCount();
		double nGust = qMax * cna(fc, f, mach[iq]) * alphaGust / share;
		Load gustLoad = new Load(nGust, qMax, alphaGust, mach[iq], t[iq], "crosswind gust at max q");
		if (ia >= 0) {
			double qa = 0.5 * rho[ia] * br.airspeed(ia) * br.airspeed(ia);
			double n = qa * cna(fc, f, mach[ia]) * Math.abs(aoa[ia]) / share;
			if (n > nGust) {
				return new Load(n, qa, Math.abs(aoa[ia]), mach[ia], t[ia], "largest simulated q x angle of attack");
			}
		}
		return gustLoad;
	}

	/** Normal-force slope of the fin set, N per (Pa rad). */
	static double cna(FlightConfiguration fc, FinSet f, double mach) {
		FlightConditions c = new FlightConditions(fc);
		c.setMach(Math.max(0.05, Double.isNaN(mach) ? 0.3 : mach));
		c.setAOA(0);
		c.setRollRate(0);
		Map<RocketComponent, AerodynamicForces> map = new BarrowmanCalculator().getForceAnalysis(fc, c, new WarningSet());
		for (Map.Entry<RocketComponent, AerodynamicForces> e : map.entrySet()) {
			if (e.getKey().getID().equals(f.getID()) && e.getValue().getCP() != null) {
				return e.getValue().getCP().weight * fc.getReferenceArea();
			}
		}
		throw new ToolException("OpenRocket gives no normal force for " + f.getName() + ".");
	}

	/** Input deck plus the node numbers used to classify modes. */
	public record Deck(String text, int[] leadingEdge, int[] trailingEdge, int elements, Plate plate) {
		int tipLeadingEdge() {
			return leadingEdge[leadingEdge.length - 1];
		}

		int tipTrailingEdge() {
			return trailingEdge[trailingEdge.length - 1];
		}
	}

	/** CalculiX deck: S8R mesh of nc x ns elements, clamped root, uniform pressure, static step and {@code modes} modes. */
	public static Deck deck(Plate p, Material m, double pressure, int nc, int ns, int modes) {
		StringBuilder b = new StringBuilder();
		b.append("** Fin plate from openrocket-mcp: SI units (m, N, Pa, kg/m3). x along the root chord, y along the span,\n")
				.append("** root clamped (y = 0). Step 1: uniform pressure (design load); step 2: natural frequencies.\n*NODE\n");
		int[][] id = new int[2 * nc + 1][2 * ns + 1];
		int k = 0;
		for (int j = 0; j <= 2 * ns; j++) {
			double v = (double) j / (2 * ns);
			double xLe = p.sweep() * v, chord = p.root() + (p.tip() - p.root()) * v;
			for (int i = 0; i <= 2 * nc; i++) {
				if (i % 2 == 1 && j % 2 == 1) {
					continue; // 8-node elements have no centre node
				}
				id[i][j] = ++k;
				b.append(String.format(Locale.ROOT, "%d,%.9e,%.9e,0%n", k, xLe + chord * i / (2 * nc), p.span() * v));
			}
		}
		b.append("*ELEMENT,TYPE=S8R,ELSET=EALL\n");
		int e = 0;
		for (int j = 0; j < ns; j++) {
			for (int i = 0; i < nc; i++) {
				int x = 2 * i, y = 2 * j;
				b.append(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%d,%d%n", ++e, id[x][y], id[x + 2][y], id[x + 2][y + 2],
						id[x][y + 2], id[x + 1][y], id[x + 2][y + 1], id[x + 1][y + 2], id[x][y + 1]));
			}
		}
		b.append("*NSET,NSET=ROOT\n");
		for (int i = 0; i <= 2 * nc; i++) {
			b.append(id[i][0]).append(i % 8 == 7 || i == 2 * nc ? "\n" : ",");
		}
		b.append(String.format(Locale.ROOT, "*NSET,NSET=NALL,GENERATE%n1,%d,1%n", k));
		b.append("*MATERIAL,NAME=FIN\n*ELASTIC,TYPE=ENGINEERING CONSTANTS\n");
		b.append(String.format(Locale.ROOT, "%.6e,%.6e,%.6e,%.4f,%.4f,%.4f,%.6e,%.6e,%n%.6e%n", m.e(), m.e(), m.e(), m.nu(), m.nu(), m.nu(),
				m.g(), m.g(), m.g()));
		b.append(String.format(Locale.ROOT, "*DENSITY%n%.6e%n", m.density()));
		b.append("*ORIENTATION,NAME=CHORD\n1.,0.,0.,0.,1.,0.\n");
		b.append(String.format(Locale.ROOT, "*SHELL SECTION,ELSET=EALL,MATERIAL=FIN,ORIENTATION=CHORD%n%.6e%n", p.thickness()));
		b.append("*BOUNDARY\nROOT,1,6,0\n");
		b.append(String.format(Locale.ROOT, "*STEP%n*STATIC%n*DLOAD%nEALL,P,%.6e%n", pressure));
		b.append("*NODE PRINT,NSET=NALL\nU\n*NODE FILE,OUTPUT=3D\nU\n*EL FILE,OUTPUT=3D\nS\n*END STEP\n");
		b.append(String.format(Locale.ROOT, "*STEP%n*FREQUENCY%n%d%n*NODE PRINT,NSET=NALL%nU%n*END STEP%n", modes));
		int[] le = new int[2 * ns + 1], te = new int[2 * ns + 1];
		for (int j = 0; j <= 2 * ns; j++) {
			le[j] = id[0][j];
			te[j] = id[2 * nc][j];
		}
		return new Deck(b.toString(), le, te, e, p);
	}

	/** One natural mode. */
	public record Mode(double frequency, String kind) {
	}

	/** What CalculiX found. */
	/**
	 * Peak stresses over the whole fin, and away from the two root corners (outside 10% of the root chord from each),
	 * where the idealised clamp makes the stress grow with mesh refinement.
	 */
	public record Result(double maxDeflection, double maxVonMises, double maxPrincipal, double vonMisesAwayFromCorners,
			double principalAwayFromCorners, List<Mode> modes) {
	}

	/** A CalculiX executable: $CCX, else ccx / ccx_static / ccx_dynamic / ccx_2.2x on the PATH; null if none. */
	public static String findCcx(String explicit) {
		if (explicit != null && !explicit.isBlank()) {
			return new File(explicit).canExecute() ? explicit : null;
		}
		String env = System.getenv("CCX");
		if (env != null && !env.isBlank() && new File(env).canExecute()) {
			return env;
		}
		String path = System.getenv("PATH");
		if (path == null) {
			return null;
		}
		String[] names = { "ccx", "ccx_static", "ccx_dynamic", "ccx_2.23", "ccx_2.22", "ccx_2.21", "ccx_2.20" };
		for (String dir : path.split(File.pathSeparator)) {
			for (String n : names) {
				for (String ext : new String[] { "", ".exe" }) {
					File f = new File(dir, n + ext);
					if (f.isFile() && f.canExecute()) {
						return f.getAbsolutePath();
					}
				}
			}
		}
		return null;
	}

	/** Runs CalculiX on {@code dir/job.inp} and reads the results. */
	public static Result run(String ccx, Path dir, String job, Deck deck, long timeoutSeconds) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(ccx, "-i", job).directory(dir.toFile()).redirectErrorStream(true)
				.redirectOutput(dir.resolve(job + ".log").toFile());
		pb.environment().putIfAbsent("OMP_NUM_THREADS", "2");
		Process pr = pb.start();
		if (!pr.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			pr.destroyForcibly();
			throw new ToolException("CalculiX did not finish within " + timeoutSeconds + " s.");
		}
		Path dat = dir.resolve(job + ".dat"), frd = dir.resolve(job + ".frd");
		if (pr.exitValue() != 0 || !Files.exists(dat) || !Files.exists(frd)) {
			String log = Files.exists(dir.resolve(job + ".log")) ? Files.readString(dir.resolve(job + ".log")) : "";
			throw new ToolException("CalculiX failed (exit " + pr.exitValue() + "): " + tail(log, 600));
		}
		return parse(Files.readString(dat), Files.readString(frd), deck);
	}

	private static String tail(String s, int n) {
		String t = s.strip();
		return t.length() <= n ? t : "..." + t.substring(t.length() - n);
	}

	/** Reads the static deflection and stresses and the natural frequencies. */
	static Result parse(String dat, String frd, Deck deck) {
		String[] lines = dat.split("\\R");
		// Displacement blocks: the first is the static step, then one per mode.
		java.util.Set<Integer> edge = new java.util.HashSet<>();
		for (int n : deck.leadingEdge()) {
			edge.add(n);
		}
		for (int n : deck.trailingEdge()) {
			edge.add(n);
		}
		List<Map<Integer, Double>> blocks = new ArrayList<>(); // per block: uz of the edge nodes
		List<Double> maxU = new ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].strip().startsWith("displacements")) {
				Map<Integer, Double> uz = new java.util.HashMap<>();
				double mu = 0;
				for (int j = i + 1; j < lines.length; j++) {
					String l = lines[j].strip();
					if (l.isEmpty()) {
						if (j > i + 1) {
							break;
						}
						continue;
					}
					String[] w = l.split("\\s+");
					if (w.length < 4 || !w[0].matches("\\d+")) {
						break;
					}
					int node = Integer.parseInt(w[0]);
					double ux = Double.parseDouble(w[1]), uy = Double.parseDouble(w[2]), z = Double.parseDouble(w[3]);
					mu = Math.max(mu, Math.sqrt(ux * ux + uy * uy + z * z));
					if (edge.contains(node)) {
						uz.put(node, z);
					}
				}
				blocks.add(uz);
				maxU.add(mu);
			}
		}
		List<Double> freqs = new ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].contains("E I G E N V A L U E   O U T P U T")) {
				for (int j = i + 1; j < lines.length && freqs.size() < 50; j++) {
					String[] w = lines[j].strip().split("\\s+");
					if (w.length >= 4 && w[0].matches("\\d+")) {
						freqs.add(Double.parseDouble(w[3]));
					} else if (!freqs.isEmpty() && lines[j].strip().isEmpty()) {
						break;
					}
				}
				break;
			}
		}
		List<Mode> modes = new ArrayList<>();
		for (int m = 0; m < freqs.size(); m++) {
			Map<Integer, Double> b = m + 1 < blocks.size() ? blocks.get(m + 1) : Map.of();
			modes.add(new Mode(freqs.get(m), kind(line(b, deck.leadingEdge()), line(b, deck.trailingEdge()))));
		}
		double[] s = frdStress(frd, deck.plate());
		return new Result(maxU.isEmpty() ? Double.NaN : maxU.get(0), s[0], s[1], s[2], s[3], modes);
	}

	private static double[] line(Map<Integer, Double> uz, int[] nodes) {
		double[] v = new double[nodes.length];
		for (int i = 0; i < nodes.length; i++) {
			v[i] = uz.getOrDefault(nodes[i], 0.0);
		}
		return v;
	}

	/**
	 * Mode shape from the out-of-plane motion along the leading and trailing edges (root to tip): bending when the
	 * edges move together, torsion when they move apart; the order is one more than the nodal lines crossing the
	 * edge.
	 */
	static String kind(double[] le, double[] te) {
		double tl = le[le.length - 1], tt = te[te.length - 1];
		double big = 0;
		for (int i = 0; i < le.length; i++) {
			big = Math.max(big, Math.max(Math.abs(le[i]), Math.abs(te[i])));
		}
		if (big < 1e-30) {
			return "in-plane";
		}
		double[] sum = new double[le.length], diff = new double[le.length];
		for (int i = 0; i < le.length; i++) {
			sum[i] = le[i] + te[i];
			diff[i] = le[i] - te[i];
		}
		boolean together = tl * tt > 0 && Math.min(Math.abs(tl), Math.abs(tt)) > 0.3 * Math.max(Math.abs(tl), Math.abs(tt));
		boolean apart = tl * tt < 0 && Math.min(Math.abs(tl), Math.abs(tt)) > 0.3 * Math.max(Math.abs(tl), Math.abs(tt));
		if (together) {
			return ordinal(signChanges(sum, big)) + " bending";
		}
		if (apart) {
			return ordinal(signChanges(diff, big)) + " torsion";
		}
		return "mixed bending-torsion";
	}

	/** Sign changes along a line, ignoring values within 5% of the peak (near the clamped root). */
	static int signChanges(double[] v, double big) {
		int n = 0;
		double last = 0;
		for (double x : v) {
			if (Math.abs(x) < 0.05 * big) {
				continue;
			}
			if (last != 0 && x * last < 0) {
				n++;
			}
			last = x;
		}
		return n;
	}

	private static String ordinal(int changes) {
		return switch (changes) {
			case 0 -> "1st";
			case 1 -> "2nd";
			case 2 -> "3rd";
			default -> (changes + 1) + "th";
		};
	}

	/**
	 * Maximum von Mises and absolute principal stress of the first (static) STRESS block of an .frd file: {over all
	 * nodes, over nodes outside 10% of the root chord from either root corner}.
	 */
	static double[] frdStress(String frd, Plate p) {
		double vm = 0, pr = 0, vmAway = 0, prAway = 0;
		boolean in = false, seen = false, coords = false;
		Map<Integer, double[]> xyz = new java.util.HashMap<>();
		double keep = 0.1 * p.root();
		for (String l : frd.split("\\R")) {
			if (l.startsWith("    2C")) {
				coords = true;
				continue;
			}
			if (coords) {
				if (l.startsWith(" -1") && l.length() >= 13 + 36) {
					xyz.put(Integer.parseInt(l.substring(3, 13).trim()), new double[] { num(l, 0), num(l, 1), num(l, 2) });
					continue;
				}
				coords = false;
			}
			if (l.startsWith(" -4  STRESS")) {
				in = !seen;
				seen = true;
				continue;
			}
			if (l.startsWith(" -4") || l.startsWith(" -3")) {
				in = false;
				continue;
			}
			if (in && l.startsWith(" -1") && l.length() >= 13 + 72) {
				double[] v = new double[6];
				for (int k = 0; k < 6; k++) {
					v[k] = num(l, k);
				}
				// frd order: sxx syy szz sxy syz szx
				double m = vonMises(v[0], v[1], v[2], v[3], v[4], v[5]), q = 0;
				for (double x : principal(v[0], v[1], v[2], v[3], v[4], v[5])) {
					q = Math.max(q, Math.abs(x));
				}
				vm = Math.max(vm, m);
				pr = Math.max(pr, q);
				double[] c = xyz.get(Integer.parseInt(l.substring(3, 13).trim()));
				boolean corner = c != null && (Math.hypot(c[0], c[1]) < keep || Math.hypot(c[0] - p.root(), c[1]) < keep);
				if (!corner) {
					vmAway = Math.max(vmAway, m);
					prAway = Math.max(prAway, q);
				}
			}
		}
		return new double[] { vm, pr, vmAway, prAway };
	}

	private static double num(String l, int k) {
		return Double.parseDouble(l.substring(13 + 12 * k, 25 + 12 * k).trim());
	}

	static double vonMises(double sx, double sy, double sz, double txy, double tyz, double tzx) {
		return Math.sqrt(0.5 * ((sx - sy) * (sx - sy) + (sy - sz) * (sy - sz) + (sz - sx) * (sz - sx))
				+ 3 * (txy * txy + tyz * tyz + tzx * tzx));
	}

	/** Principal stresses of a symmetric tensor (trigonometric solution of the characteristic cubic). */
	static double[] principal(double sx, double sy, double sz, double txy, double tyz, double tzx) {
		double i1 = sx + sy + sz;
		double i2 = sx * sy + sy * sz + sz * sx - txy * txy - tyz * tyz - tzx * tzx;
		double i3 = sx * sy * sz + 2 * txy * tyz * tzx - sx * tyz * tyz - sy * tzx * tzx - sz * txy * txy;
		double p = i1 * i1 / 3 - i2, q = 2 * i1 * i1 * i1 / 27 - i1 * i2 / 3 + i3;
		if (p < 1e-30 * (1 + i1 * i1)) {
			return new double[] { i1 / 3, i1 / 3, i1 / 3 };
		}
		double r = Math.sqrt(p / 3), c = Math.max(-1, Math.min(1, q / (2 * r * r * r)));
		double phi = Math.acos(c) / 3;
		return new double[] { i1 / 3 + 2 * r * Math.cos(phi), i1 / 3 + 2 * r * Math.cos(phi - 2 * Math.PI / 3),
				i1 / 3 + 2 * r * Math.cos(phi + 2 * Math.PI / 3) };
	}

	// ------------------------------------------------------------------------------------------- hand estimates

	/** Root bending stress of the plate strip at the root under a uniform pressure: 6 M / (c t^2). */
	public static double rootStress(Plate p, double finForce) {
		return 6 * finForce * p.centroidSpan() / (p.root() * p.thickness() * p.thickness());
	}

	/** First bending frequency of a uniform cantilever plate strip of the fin's span: 3.516 / (2 pi L^2) sqrt(E t^2 / 12 rho). */
	public static double bendingFrequency(Plate p, Material m) {
		return 3.516 / (2 * Math.PI * p.span() * p.span()) * Math.sqrt(m.e() * p.thickness() * p.thickness() / (12 * m.density()));
	}
}
