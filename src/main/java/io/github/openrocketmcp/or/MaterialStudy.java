package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.ExternalComponent;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Material trade study: the chosen parts (fins, airframe tubes, nose cone, or named components) built from each
 * candidate material (and optionally each stock thickness), flown in OpenRocket on a copy of the design, and checked
 * with everything a material changes: mass and so apogee and stability, fin flutter (shear modulus), fin root bending
 * (strength), aerodynamic heating (service temperature), and tube wall stress and buckling (strength, Young's modulus).
 * Densities are OpenRocket's; the other properties come from the team standards, with their sources.
 */
public final class MaterialStudy {
	private MaterialStudy() {
	}

	/** Which parts a study changes. */
	public enum Group {
		FINS, TUBES, NOSE, TRANSITIONS, AIRFRAME, NAMED
	}

	/** Default candidates per group, OpenRocket material names. */
	static List<String> defaults(Group g) {
		return switch (g) {
			case FINS -> List.of("Fiberglass", "Carbon fiber", "Aluminum", "Plywood (birch)");
			case TUBES -> List.of("Fiberglass", "Carbon fiber", "Blue tube", "Kraft phenolic", "Aluminum");
			case NOSE -> List.of("Fiberglass", "Carbon fiber", "PLA - 100% infill", "Aluminum");
			case TRANSITIONS, AIRFRAME -> List.of("Fiberglass", "Carbon fiber", "Aluminum", "Blue tube");
			default -> List.of("Fiberglass", "Carbon fiber", "Aluminum");
		};
	}

	/**
	 * Parts selected by a keyword ("fins", "body tubes", "nose cone", "transitions", or "airframe" for the whole
	 * aerodynamic body: nose cone, body tubes and transitions) or by component id / name.
	 */
	public record Selection(Group group, List<String> ids, List<String> names) {
	}

	public static Selection select(Rocket r, List<String> refs) {
		Set<RocketComponent> found = new LinkedHashSet<>();
		Group group = null;
		for (String ref : refs) {
			String k = ref.trim().toLowerCase(Locale.ROOT);
			Group g = switch (k) {
				case "fins", "fin", "fin sets", "fin set" -> Group.FINS;
				case "body tubes", "body tube", "tubes", "airframe tubes" -> Group.TUBES;
				case "nose", "nose cone", "nosecone" -> Group.NOSE;
				case "transitions", "transition", "boattail", "boat tail", "shoulders" -> Group.TRANSITIONS;
				case "airframe", "aerobody", "aero body", "body", "outer mold line", "shell" -> Group.AIRFRAME;
				default -> null;
			};
			List<RocketComponent> add = new ArrayList<>();
			if (g == null) {
				RocketComponent c = Components.find(r, ref);
				if (!(c instanceof ExternalComponent)) {
					throw new ToolException("'" + c.getName() + "' is not an airframe part with a material (fins, tubes, nose cone, "
							+ "transitions); internal parts are usually given as masses.");
				}
				add.add(c);
				g = c instanceof FinSet ? Group.FINS : c instanceof NoseCone ? Group.NOSE : c instanceof BodyTube ? Group.TUBES
						: c instanceof Transition ? Group.TRANSITIONS : Group.NAMED;
			} else {
				for (RocketComponent c : r) {
					// Airframe tubes are the stages' own body tubes (a hybrid's combustion chamber too), not inner tubes;
					// a nose cone is also a transition in OpenRocket, so it is told apart first.
					boolean stagePart = c.getParent() instanceof AxialStage;
					boolean nose = c instanceof NoseCone, tube = c instanceof BodyTube && stagePart;
					boolean transition = c instanceof Transition && !nose && stagePart;
					if (g == Group.FINS && c instanceof FinSet || g == Group.NOSE && nose || g == Group.TUBES && tube
							|| g == Group.TRANSITIONS && transition || g == Group.AIRFRAME && (nose || tube || transition)) {
						add.add(c);
					}
				}
				if (add.isEmpty()) {
					throw new ToolException("The design has no " + k + ".");
				}
			}
			found.addAll(add);
			group = group == null ? g : group == g ? g : Group.NAMED;
		}
		List<String> ids = new ArrayList<>(), names = new ArrayList<>();
		for (RocketComponent c : found) {
			ids.add(c.getID().toString());
			names.add(c.getName());
		}
		return new Selection(group, ids, names);
	}

	/** Material properties used, SI (NaN = not in the standards) and where each came from. */
	public record Props(String name, double density, double shear, double youngs, double strength, double maxTemp,
			double poisson, List<String> sources) {
	}

	static Props props(Standards std, Material m) {
		List<String> src = new ArrayList<>();
		src.add("density " + Units.fmt(m.getDensity(), Dim.DENSITY) + " (OpenRocket)");
		double[] v = new double[4];
		String[] tables = { "structures.shearModulus", "structures.youngsModulus", "structures.strength", "structures.maxServiceTemperature" };
		Dim[] dims = { Dim.PRESSURE, Dim.PRESSURE, Dim.PRESSURE, Dim.TEMPERATURE };
		String[] labels = { "G", "E", "strength", "max service temperature" };
		for (int i = 0; i < 4; i++) {
			Object[] x = std.materialValue(tables[i], m.getName(), dims[i]);
			v[i] = x == null ? Double.NaN : (Double) x[0];
			if (x != null) {
				src.add(labels[i] + " " + Units.fmt(v[i], dims[i]) + " (" + x[1] + ")");
			}
		}
		Object[] nu = std.materialValue("structures.poissonRatio", m.getName(), Dim.DIMENSIONLESS);
		return new Props(m.getName(), m.getDensity(), v[0], v[1], v[2], v[3], nu == null ? 0.3 : (Double) nu[0], src);
	}

	/** One candidate: a material for every selected part, and optionally a thickness (fins, tube wall, nose shell). */
	public record Option(String label, Material material, double thickness, boolean current) {
	}

	/** One option's results, SI (NaN = not applicable / unknown). */
	public record Row(Option option, double massChange, double launchMass, double apogee, double maxMach, double minStability,
			double maxStability, double railExit, double flutterMargin, double finStressMargin, double tempMargin,
			double maxTemp, double tubeStressMargin, double bucklingMargin, List<String> unchecked, List<String> notes, String error) {
	}

	/**
	 * Builds the edit that gives every selected part material m (and thickness t unless NaN), keeping the team's
	 * weigh-ins. A part weighed heavier than its model carries hardware: its override moves by the computed mass
	 * difference. A part weighed lighter than its model is the model's shell made lighter (a thinner wall, a lighter
	 * layup): its override scales with the computed mass. A parent overriding its subcomponents moves by the difference.
	 */
	static java.util.function.Consumer<Rocket> edit(Selection sel, Material m, double t, List<String> notes) {
		return r -> {
			for (String id : sel.ids()) {
				RocketComponent c = Components.find(r, id);
				double before = c.getComponentMass();
				if (c instanceof ExternalComponent ext && m != null) {
					ext.setMaterial(m);
				}
				if (!Double.isNaN(t)) {
					if (c instanceof FinSet f) {
						f.setThickness(t);
					} else if (c instanceof SymmetricComponent sc) {
						sc.setThickness(t);
					}
				}
				double after = c.getComponentMass(), delta = after - before;
				for (RocketComponent p = c; p != null && Math.abs(delta) > 1e-6; p = p.getParent()) {
					if (!p.isMassOverridden() || p != c && !p.isSubcomponentsOverriddenMass()) {
						continue;
					}
					double was = p.getOverrideMass();
					boolean scale = p == c && was < before && before > 0;
					p.setOverrideMass(Math.max(0, scale ? was * after / before : was + delta));
					if (notes != null) {
						double change = p.getOverrideMass() - was;
						notes.add("'" + p.getName() + "' is weighed (mass overridden): " + (change >= 0 ? "+" : "")
								+ Units.fmt(change, Dim.MASS) + (p == c ? "" : " for " + c.getName())
								+ (scale ? " (scaled: weighed lighter than modelled)" : ""));
					}
					break;
				}
			}
		};
	}

	public static Map<String, Object> study(Simulation base, OpenRocketDocument doc, Standards std, Selection sel,
			List<String> materials, List<Double> thicknesses, double minStability) {
		Rocket rocket = base.getRocket();
		RocketComponent first = Components.find(rocket, sel.ids().get(0));
		Material now = first instanceof ExternalComponent ext ? ext.getMaterial() : null;
		List<String> mats = materials == null || materials.isEmpty() ? defaults(sel.group()) : materials;
		List<Option> opts = new ArrayList<>();
		opts.add(new Option("current: " + (now == null ? "?" : now.getName()) + mixed(rocket, sel, now), null, Double.NaN, true));
		Map<String, Props> used = new LinkedHashMap<>();
		if (now != null) {
			used.put(now.getName(), props(std, now));
		}
		for (String name : mats) {
			Material m;
			try {
				m = Components.material(first, Material.Type.BULK, name);
			} catch (IllegalArgumentException e) {
				throw new ToolException(e.getMessage());
			}
			used.putIfAbsent(m.getName(), props(std, m));
			if (thicknesses == null || thicknesses.isEmpty()) {
				if (now != null && m.getName().equals(now.getName()) && mixed(rocket, sel, now).isEmpty()) {
					continue; // that is the current row
				}
				opts.add(new Option(m.getName(), m, Double.NaN, false));
			} else {
				for (double t : thicknesses) {
					opts.add(new Option(m.getName() + ", " + Units.fmt(t, Dim.LENGTH), m, t, false));
				}
			}
		}
		if (opts.size() > 41) {
			throw new ToolException(opts.size() - 1 + " options: at most 40 (materials x thicknesses).");
		}

		List<Simulation> sims = new ArrayList<>();
		List<List<String>> notes = new ArrayList<>();
		for (Option o : opts) {
			List<String> n = new ArrayList<>();
			notes.add(n);
			sims.add(Variants.ascentOnly(Variants.of(base, doc, o.current() ? null : edit(sel, o.material(), o.thickness(), n), null)));
		}
		CallContext.current().expect(sims.size(), "materials flown");
		List<Variants.Run> runs = Variants.runAll(sims);

		double gust = Double.isNaN(std.rule("maxGroundWind.value", Dim.VELOCITY)) ? 30 / 3.6 : std.rule("maxGroundWind.value", Dim.VELOCITY);
		double sf = std.q("structures.loadSafetyFactor", Dim.DIMENSIONLESS, 2);
		double flutterNeed = std.q("structures.flutterMinMargin", Dim.DIMENSIONLESS, 1.5);
		List<Row> rows = new ArrayList<>();
		double baseMass = Double.NaN;
		for (int i = 0; i < runs.size(); i++) {
			Variants.Run run = runs.get(i);
			Option o = opts.get(i);
			if (!run.ok()) {
				rows.add(new Row(o, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
						Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, List.of(), notes.get(i), run.error()));
				continue;
			}
			rows.add(evaluate(run.sim(), o, sel, std, gust, notes.get(i)));
			if (o.current()) {
				baseMass = rows.get(rows.size() - 1).launchMass();
			}
		}
		final double m0 = baseMass;
		rows.replaceAll(r -> new Row(r.option(), r.launchMass() - m0, r.launchMass(), r.apogee(), r.maxMach(), r.minStability(),
				r.maxStability(), r.railExit(), r.flutterMargin(), r.finStressMargin(), r.tempMargin(), r.maxTemp(), r.tubeStressMargin(),
				r.bucklingMargin(), r.unchecked(), r.notes(), r.error()));
		return render(rows, used, sel, minStability, flutterNeed, sf, gust);
	}

	private static String mixed(Rocket r, Selection sel, Material now) {
		for (String id : sel.ids()) {
			if (Components.find(r, id) instanceof ExternalComponent e && now != null && !e.getMaterial().getName().equals(now.getName())) {
				return " (mixed)";
			}
		}
		return "";
	}

	static Row evaluate(Simulation sim, Option o, Selection sel, Standards std, double gust, List<String> notes) {
		var data = sim.getSimulatedData();
		FlightDataBranch b = data.getBranch(0);
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		Sims.Window w = Sims.ascentStability(b);
		double launchMass = MassCalculator.calculateLaunch(fc).getMass();
		double sf = std.q("structures.loadSafetyFactor", Dim.DIMENSIONLESS, 2);

		double flutter = Double.NaN, finStress = Double.NaN, tempMargin = Double.NaN, maxTemp = Double.NaN, tube = Double.NaN,
				buckling = Double.NaN;
		Branch br = Branch.of(b);
		double[] temp = br.col(FlightDataType.TYPE_AIR_TEMPERATURE), mach = br.col(FlightDataType.TYPE_MACH_NUMBER);
		Map<String, double[]> stresses = null;
		Set<String> unchecked = new LinkedHashSet<>();
		for (String id : sel.ids()) {
			RocketComponent c = Components.find(sim.getRocket(), id);
			Material m = c instanceof ExternalComponent e ? e.getMaterial() : null;
			if (m == null) {
				continue;
			}
			Props p = props(std, m);
			if (c instanceof FinSet f) {
				if (!Double.isNaN(p.shear())) {
					for (Structures.FlutterResult fr : Structures.flutter(sim, std, null, Double.NaN)) {
						if (fr.fin().getID().equals(f.getID()) && !Double.isNaN(fr.minMargin())) {
							flutter = min(flutter, fr.minMargin());
						}
					}
				} else {
					unchecked.add("fin flutter (no shear modulus for " + m.getName() + ")");
				}
				if (!Double.isNaN(p.strength())) {
					FinFea.Plate plate = FinFea.plate(f, Double.NaN);
					FinFea.Load load = FinFea.designLoad(sim, f, gust);
					double s = FinFea.rootStress(plate, load.finForce());
					finStress = min(finStress, p.strength() / (s * sf));
				} else {
					unchecked.add("fin strength (no strength for " + m.getName() + ")");
				}
			}
			// Heating: stagnation at the nose tip and fin leading edges, turbulent recovery on body surfaces.
			boolean stag = c instanceof NoseCone || c instanceof FinSet;
			double worst = 0;
			for (int i = 0; i < temp.length; i++) {
				if (!Double.isNaN(temp[i]) && !Double.isNaN(mach[i])) {
					worst = Math.max(worst, stag ? Heating.stagnation(temp[i], mach[i]) : Heating.recovery(temp[i], mach[i]));
				}
			}
			maxTemp = Double.isNaN(maxTemp) ? worst : Math.max(maxTemp, worst);
			if (!Double.isNaN(p.maxTemp())) {
				tempMargin = min(tempMargin, p.maxTemp() - worst); // degrees below the limit
			}
			if (c instanceof SymmetricComponent shell) {
				String kind = c instanceof BodyTube ? "tube" : c instanceof NoseCone ? "nose cone" : "transition";
				if (Double.isNaN(p.strength()) || Double.isNaN(p.youngs())) {
					unchecked.add(kind + " " + (Double.isNaN(p.strength()) ? "strength" : "buckling") + " (no "
							+ (Double.isNaN(p.strength()) ? "strength" : "Young's modulus") + " for " + m.getName() + ")");
				}
				if (stresses == null) {
					stresses = Loads.wallStresses(sim, gust);
				}
				double own = shell.getThickness();
				for (Map.Entry<String, double[]> e : stresses.entrySet()) {
					if (!List.of(e.getKey().split(" / ")).contains(c.getName()) || !(own > 0)) {
						continue;
					}
					// The joint's stress uses the thinner neighbour's wall; this part's own wall carries it here.
					double[] st = e.getValue();
					if (!Double.isNaN(p.strength()) && st[0] > 0) {
						tube = min(tube, p.strength() / (st[0] * st[3] / own * sf));
					}
					if (!Double.isNaN(p.youngs()) && st[1] > 0) {
						buckling = min(buckling, shellBuckling(shell, p, own, st[2]) / (st[1] * st[3] / own * sf));
					}
				}
			}
		}
		return new Row(o, 0, launchMass, data.getMaxAltitude(), data.getMaxMachNumber(), w == null ? Double.NaN : w.min(),
				w == null ? Double.NaN : w.max(), data.getLaunchRodVelocity(), flutter, finStress, tempMargin, maxTemp, tube, buckling,
				new ArrayList<>(unchecked), notes, null);
	}

	/**
	 * Critical axial stress of a thin shell, its own wall t at radius r. A tube: the cylinder's classical buckling stress
	 * with NASA SP-8007's knockdown, sigma = gamma 0.605 E t / r with gamma = 1 - 0.901 (1 - exp(-sqrt(r/t) / 16)). A
	 * nose cone or transition: NASA SP-8019's cone under axial load, P = gamma 2 pi E t^2 cos^2(alpha) / sqrt(3 (1 - nu^2))
	 * with gamma = 0.33, as a stress over the wall at r (alpha the half-angle from the end radii and length).
	 */
	static double shellBuckling(SymmetricComponent c, Props p, double t, double r) {
		if (c instanceof BodyTube) {
			double gamma = 1 - 0.901 * (1 - Math.exp(-Math.sqrt(r / t) / 16));
			return gamma * 0.605 * p.youngs() * t / r;
		}
		double alpha = Math.atan2(Math.abs(c.getAftRadius() - c.getForeRadius()), Math.max(1e-6, c.getLength()));
		double pcr = 0.33 * 2 * Math.PI * p.youngs() * t * t * Math.cos(alpha) * Math.cos(alpha)
				/ Math.sqrt(3 * (1 - p.poisson() * p.poisson()));
		return pcr / (2 * Math.PI * r * t);
	}

	private static double min(double a, double b) {
		return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.min(a, b);
	}

	/** Material checks an option fails (flutter, strength, buckling), in words; empty when it passes those made. */
	static List<String> failures(Row r, double flutterNeed) {
		List<String> f = new ArrayList<>();
		if (r.flutterMargin() < flutterNeed) {
			f.add("fin flutter margin " + Units.num(r.flutterMargin()) + " < " + Units.num(flutterNeed));
		}
		if (r.finStressMargin() < 1) {
			f.add("fin root bending over the allowable");
		}
		if (r.tubeStressMargin() < 1) {
			f.add("wall stress over the allowable");
		}
		if (r.bucklingMargin() < 1) {
			f.add("wall buckling");
		}
		return f;
	}

	private static boolean passes(Row r, double flutterNeed) {
		return r.error() == null && failures(r, flutterNeed).isEmpty() && r.unchecked().isEmpty();
	}

	private static boolean stable(Row r, double minStability) {
		return Double.isNaN(minStability) || r.minStability() >= minStability;
	}

	static Map<String, Object> render(List<Row> rows, Map<String, Props> used, Selection sel, double minStability,
			double flutterNeed, double sf, double gust) {
		Row base = rows.get(0);
		// Options whose material passes first, then those also meeting the stability floor, then by apogee.
		List<Row> sorted = new ArrayList<>(rows);
		sorted.sort((a, b) -> {
			if (passes(a, flutterNeed) != passes(b, flutterNeed)) {
				return passes(a, flutterNeed) ? -1 : 1;
			}
			if (stable(a, minStability) != stable(b, minStability)) {
				return stable(a, minStability) ? -1 : 1;
			}
			return Double.compare(nz(b.apogee()), nz(a.apogee()));
		});
		List<Map<String, Object>> out = new ArrayList<>();
		for (Row r : sorted) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("option", r.option().label());
			if (r.error() != null) {
				m.put("error", r.error());
				out.add(m);
				continue;
			}
			m.put("launchMass", Units.fmt(r.launchMass(), Dim.MASS) + (r.option().current() ? "" : " (" + signed(r.massChange(), Dim.MASS) + ")"));
			m.put("apogee", Units.fmt(r.apogee(), Dim.DISTANCE) + (r.option().current() ? "" : " (" + signed(r.apogee() - base.apogee(), Dim.DISTANCE) + ")"));
			m.put("stability", Units.num(r.minStability()) + " to " + Units.num(r.maxStability()) + " cal (ascent)");
			m.put("railExit", Units.fmt(r.railExit(), Dim.VELOCITY));
			if (!Double.isNaN(r.flutterMargin())) {
				m.put("finFlutterMargin", Units.num(r.flutterMargin()) + " (need " + Units.num(flutterNeed) + ")");
			}
			if (!Double.isNaN(r.finStressMargin())) {
				m.put("finRootBending", "strength / (stress x " + Units.num(sf) + ") = " + Units.num(r.finStressMargin()));
			}
			if (!Double.isNaN(r.maxTemp())) {
				m.put("heating", Units.fmt(r.maxTemp(), Dim.TEMPERATURE) + " surface"
						+ (Double.isNaN(r.tempMargin()) ? " (no service temperature in the standards)"
								: r.tempMargin() >= 0 ? ", " + Units.num(r.tempMargin()) + " K below the material limit"
										: ", " + Units.num(-r.tempMargin()) + " K ABOVE the material limit"));
			}
			if (!Double.isNaN(r.tubeStressMargin())) {
				m.put("wallStress", "strength / (stress x " + Units.num(sf) + ") = " + Units.num(r.tubeStressMargin()));
			}
			if (!Double.isNaN(r.bucklingMargin())) {
				m.put("wallBuckling", "critical / (compression x " + Units.num(sf) + ") = " + Units.num(r.bucklingMargin()));
			}
			List<String> f = failures(r, flutterNeed);
			List<String> made = new ArrayList<>();
			if (!Double.isNaN(r.flutterMargin())) {
				made.add("flutter");
			}
			if (!Double.isNaN(r.finStressMargin()) || !Double.isNaN(r.tubeStressMargin())) {
				made.add("strength");
			}
			if (!Double.isNaN(r.bucklingMargin())) {
				made.add("buckling");
			}
			m.put("material", !f.isEmpty() ? "FAIL: " + String.join("; ", f)
					: !r.unchecked().isEmpty() ? "UNCHECKED: " + String.join("; ", r.unchecked())
							+ " in the standards; add your data to structures.* to check it"
							: made.isEmpty() ? "no structural check for these parts (heating only)" : "PASS (" + String.join(", ", made) + ")");
			if (!stable(r, minStability)) {
				m.put("stability", m.get("stability") + ": below the " + Units.num(minStability) + " cal floor (the vehicle's, not the material's)");
			}
			if (r.tempMargin() < 0) {
				m.put("heatingCheck", "CHECK: the adiabatic surface temperature exceeds this material's service temperature; real "
						+ "skins lag, so confirm with a thermal estimate, a higher-Tg resin or leading-edge protection");
			}
			if (!r.notes().isEmpty()) {
				m.put("notes", new ArrayList<>(new LinkedHashSet<>(r.notes())));
			}
			out.add(m);
		}
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("parts", sel.names());
		res.put("options", out);
		Row best = sorted.get(0);
		if (!passes(best, flutterNeed)) {
			res.put("best", "No option passes the material checks; try thicker stock (thicknesses) or a stiffer material.");
		} else {
			res.put("best", best.option().label() + ": the highest apogee of the options whose material passes"
					+ (stable(best, minStability) ? " and that meet the stability floor"
							: "; none meets the " + Units.num(minStability) + " cal stability floor, which the material alone cannot fix"));
		}
		List<Map<String, Object>> props = new ArrayList<>();
		for (Props p : used.values()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("material", p.name());
			m.put("properties", p.sources());
			props.add(m);
		}
		res.put("materials", props);
		if (sel.group() == Group.TUBES || sel.group() == Group.AIRFRAME || sel.group() == Group.NAMED) {
			res.put("pressureVessels", "Oxidizer tanks and motor cases are pressure vessels: their wall is sized by the operating "
					+ "pressure (pressure_vessel), not by these flight loads, and a composite tank is a COPV with its own rules.");
		}
		res.put("checks", List.of(
				"Mass: OpenRocket's geometry x density; where a part's mass is overridden (weighed), the override moves by the "
						+ "computed difference. Apogee, stability and rail exit come from a flight of each option (ascent).",
				"Fin flutter: NACA TN 4197 with the material's shear modulus along the flight (structures.flutterMinMargin).",
				"Fin root bending: the fin_fea hand estimate (6 M / c t^2) under a " + Units.fmt(gust, Dim.VELOCITY)
						+ " crosswind gust at max q or the largest simulated q x AoA, against the material strength / safety factor.",
				"Heating: adiabatic stagnation (nose tip, fin leading edges) or turbulent recovery (tubes) temperature against the "
						+ "material's service temperature; an upper bound (skins lag), so above it reads CHECK, not FAIL.",
				"Tubes, transitions and the nose cone: thin-wall axial + bending stress at their joints (structural_loads, on "
						+ "the part's own wall) against the strength, and axial buckling against Young's modulus: a cylinder with "
						+ "NASA SP-8007's knockdown, a cone (nose, transition) with NASA SP-8019's (0.33).",
				"Strength, moduli and temperatures are typical values from the team standards (listed above with sources): "
						+ "put your laminate's coupon data in structures.* to make the comparison yours."));
		return res;
	}

	private static double nz(double v) {
		return Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v;
	}

	private static String signed(double v, Dim d) {
		return (v >= 0 ? "+" : "") + Units.fmt(v, d);
	}
}
