package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.MassObject;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RadiusRingComponent;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.ThicknessRingComponent;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.calc.Charges;
import io.github.openrocketmcp.calc.Parachutes;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Recovery sections derived from the design: the airframe pieces of each stage (its direct nose cone / body tube /
 * transition children) are split at separation joints into independently landing, tethered sections. By default the
 * forward end of every piece holding a recovery device separates (bays open forward: main bay to nose cone, drogue bay to
 * avionics bay); callers can name the joints instead.
 */
public final class Sections {
	private Sections() {
	}

	/** One section: consecutive airframe pieces between separation joints. */
	public record Section(AxialStage stage, List<RocketComponent> pieces, double mass, List<RecoveryDevice> devices) {
		public String name() {
			return pieces.size() == 1 ? pieces.get(0).getName()
					: pieces.get(0).getName() + " .. " + pieces.get(pieces.size() - 1).getName();
		}
	}

	/** Airframe pieces of a stage in axial order. */
	static List<RocketComponent> pieces(AxialStage stage) {
		List<RocketComponent> out = new ArrayList<>();
		for (RocketComponent c : stage.getChildren()) {
			if (c instanceof SymmetricComponent && c.getLength() > 0) {
				out.add(c);
			}
		}
		return out;
	}

	/** The airframe piece (direct stage child) that contains {@code c}. */
	static RocketComponent pieceOf(RocketComponent c) {
		RocketComponent p = c;
		while (p.getParent() != null && !(p.getParent() instanceof AxialStage)) {
			p = p.getParent();
		}
		return p;
	}

	/**
	 * Splits every active stage into sections. {@code joints}: names / ids of pieces whose FORWARD end separates;
	 * null = automatic (the forward end of every piece holding a recovery device).
	 */
	public static List<Section> of(FlightConfiguration fc, Set<String> joints) {
		List<Section> out = new ArrayList<>();
		Map<RocketComponent, Double> masses = pieceMasses(fc);
		for (AxialStage stage : fc.getActiveStages()) {
			List<RocketComponent> ps = pieces(stage);
			if (ps.isEmpty()) {
				continue;
			}
			Map<RocketComponent, List<RecoveryDevice>> devs = new LinkedHashMap<>();
			for (RocketComponent c : stage) {
				if (c instanceof RecoveryDevice rd) {
					devs.computeIfAbsent(pieceOf(rd), k -> new ArrayList<>()).add(rd);
				}
			}
			List<RocketComponent> cur = new ArrayList<>();
			cur.add(ps.get(0));
			for (int i = 1; i < ps.size(); i++) {
				RocketComponent next = ps.get(i);
				boolean split = joints != null ? matches(next, joints) : devs.containsKey(next);
				if (split) {
					out.add(section(stage, cur, masses, devs));
					cur = new ArrayList<>();
				}
				cur.add(next);
			}
			out.add(section(stage, cur, masses, devs));
		}
		return out;
	}

	private static boolean matches(RocketComponent c, Set<String> refs) {
		for (String r : refs) {
			String k = r.trim().toLowerCase(Locale.ROOT);
			if (c.getName().toLowerCase(Locale.ROOT).equals(k) || (k.length() >= 6 && c.getID().toString().startsWith(k))) {
				return true;
			}
		}
		return false;
	}

	private static Section section(AxialStage stage, List<RocketComponent> pieces, Map<RocketComponent, Double> masses,
			Map<RocketComponent, List<RecoveryDevice>> devs) {
		double m = 0;
		List<RecoveryDevice> d = new ArrayList<>();
		for (RocketComponent p : pieces) {
			m += masses.getOrDefault(p, 0.0);
			d.addAll(devs.getOrDefault(p, List.of()));
		}
		return new Section(stage, List.copyOf(pieces), m, d);
	}

	/**
	 * Landing mass of each airframe piece: OpenRocket's component masses (a weighed, overridden section scaled to its
	 * override) of everything inside it, plus the burnt-out motor in any mount inside it.
	 */
	static Map<RocketComponent, Double> pieceMasses(FlightConfiguration fc) {
		Map<RocketComponent, Double> out = new LinkedHashMap<>();
		for (Map.Entry<RocketComponent, double[]> e : Loads.componentMasses(fc, null).entrySet()) {
			if (e.getKey().getParent() != null) {
				out.merge(pieceOf(e.getKey()), e.getValue()[0], Double::sum);
			}
		}
		for (AxialStage stage : fc.getActiveStages()) {
			for (RocketComponent p : pieces(stage)) {
				out.merge(p, motorBurnout(fc, p), Double::sum);
			}
		}
		return out;
	}

	static double motorBurnout(FlightConfiguration fc, RocketComponent piece) {
		double m = 0;
		for (RocketComponent c : piece) {
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				MotorConfiguration mc = mm.getMotorConfig(fc.getId());
				if (mc != null && mc.getMotor() != null) {
					m += mc.getMotor().getBurnoutMass() * Math.max(1, c.getInstanceCount());
				}
			}
		}
		return m;
	}

	// ------------------------------------------------------------------------------------------- bays

	/** Packed volume of recovery items and the free interior volume of a piece. */
	public record Bay(RocketComponent piece, double available, double packed, List<String> items) {
		public double fill() {
			return available > 0 ? packed / available : Double.NaN;
		}
	}

	public static Bay bay(RocketComponent piece) {
		double interior;
		try {
			interior = Components.interiorVolume(piece, Double.NaN);
		} catch (RuntimeException e) {
			interior = Double.NaN;
		}
		double occupied = 0, packed = 0;
		List<String> items = new ArrayList<>();
		for (RocketComponent c : piece) {
			if (c == piece) {
				continue;
			}
			double v = volume(c);
			if (c instanceof RecoveryDevice || c instanceof ShockCord) {
				packed += v;
				items.add(c.getName() + " " + Units.fmt(v, Dim.VOLUME));
			} else if (c.getParent() == piece || !(c.getParent() instanceof MassObject)) {
				occupied += v;
			}
		}
		return new Bay(piece, interior - occupied, packed, items);
	}

	/** Solid volume a component takes inside a tube. */
	static double volume(RocketComponent c) {
		if (c instanceof MassObject mo) {
			return Math.PI * mo.getRadius() * mo.getRadius() * mo.getLength();
		}
		if (c instanceof InnerTube it) {
			return Math.PI * it.getOuterRadius() * it.getOuterRadius() * it.getLength();
		}
		if (c instanceof RadiusRingComponent r) { // bulkheads, centering rings
			return Math.PI * r.getOuterRadius() * r.getOuterRadius() * r.getLength();
		}
		if (c instanceof ThicknessRingComponent t) { // couplers, engine blocks
			return Math.PI * t.getOuterRadius() * t.getOuterRadius() * t.getLength();
		}
		return 0;
	}

	// ------------------------------------------------------------------------------------------- analysis

	public static Map<String, Object> analyze(Simulation sim, Standards std, Set<String> joints) {
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		List<Section> sections = of(fc, joints);
		double limit = std.q("recovery.maxLandingEnergy", Dim.ENERGY, 75 * 1.3558179483314);
		double groundRho = Atmosphere.at(sim.getOptions().getLaunchAltitude()).density();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("jointRule", joints == null ? "automatic: the forward end of every bay holding a recovery device separates (bays open "
				+ "forward); pass joints to name the pieces whose forward end separates" : "given: " + joints);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (AxialStage stage : fc.getActiveStages()) {
			List<Section> ss = sections.stream().filter(s -> s.stage() == stage).toList();
			if (ss.isEmpty()) {
				continue;
			}
			double stageMass = ss.stream().mapToDouble(Section::mass).sum();
			FlightDataBranch b = branchOf(sim, stage);
			double v = Double.NaN;
			if (b != null) {
				FlightEvent gh = b.getFirstEvent(FlightEvent.Type.GROUND_HIT);
				if (gh != null) {
					v = Math.abs(Sims.at(b, FlightDataType.TYPE_VELOCITY_TOTAL, gh.getTime()));
				}
			}
			// Contingency: only the first-opening device(s) of the stage (e.g. main fails, drogue only).
			double drogueOnly = drogueOnlyRate(sim, stage, stageMass, groundRho);
			for (Section s : ss) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("stage", stage.getName());
				m.put("section", s.name());
				m.put("pieces", s.pieces().stream().map(p -> p.getName() + " [" + Components.shortId(p) + "]").toList());
				m.put("landingMass", Units.fmt(s.mass(), Dim.MASS));
				if (!Double.isNaN(v)) {
					double ke = Parachutes.kineticEnergy(s.mass(), v);
					m.put("landingVelocity", Units.fmt(v, Dim.VELOCITY) + " (simulated)");
					m.put("landingEnergy", Units.fmt(ke, Dim.ENERGY) + (ke <= limit ? " (within " : " (EXCEEDS ")
							+ Units.fmt(limit, Dim.ENERGY) + ")");
				}
				if (!Double.isNaN(drogueOnly)) {
					m.put("landingEnergyIfMainFails", Units.fmt(Parachutes.kineticEnergy(s.mass(), drogueOnly), Dim.ENERGY)
							+ " at " + Units.fmt(drogueOnly, Dim.VELOCITY));
				}
				List<Map<String, Object>> bays = new ArrayList<>();
				for (RocketComponent p : s.pieces()) {
					Bay bay = bay(p);
					if (bay.packed() <= 0) {
						continue;
					}
					Map<String, Object> bm = new LinkedHashMap<>();
					bm.put("bay", p.getName());
					bm.put("items", bay.items());
					bm.put("packedVolume", Units.fmt(bay.packed(), Dim.VOLUME));
					bm.put("freeVolume", Units.fmt(bay.available(), Dim.VOLUME));
					double f = bay.fill();
					bm.put("fill", Double.isNaN(f) ? "n/a" : Units.num(100 * f) + "%" + (f > 1 ? " (DOES NOT FIT)" : f > 0.8 ? " (tight)" : ""));
					double empty = Math.max(0, bay.available() - bay.packed());
					bm.put("blackPowderAt15psi", Units.fmt(Charges.blackPowderGrams(15 * 6894.757293168, empty) / 1000,
							Dim.CHARGE_MASS) + " for the empty volume (size with ejection_charge and the pin load)");
					bays.add(bm);
				}
				if (!bays.isEmpty()) {
					m.put("bays", bays);
				}
				rows.add(m);
			}
		}
		out.put("sections", rows);
		out.put("maxLandingEnergy", Units.fmt(limit, Dim.ENERGY) + " per section (recovery.maxLandingEnergy)");
		out.put("notes", List.of(
				"Section masses are the pieces' components plus burnt-out motor cases; recovery gear is counted in the piece "
						+ "it is packed in. A weighed (overridden) stage mass is shared in proportion to computed masses.",
				"Landing velocity is the simulated ground-hit speed of the stage's branch; tethered sections land at about the "
						+ "same speed. Bay volumes use OpenRocket's packed dimensions of each parachute / shock cord.",
				"Check the joints: an avionics bay glued to one tube belongs to that section; name separating pieces with joints."));
		return out;
	}

	static FlightDataBranch branchOf(Simulation sim, AxialStage stage) {
		var data = sim.getSimulatedData();
		for (FlightDataBranch b : data.getBranches()) {
			if (b.getName().equals(stage.getName())) {
				return b;
			}
		}
		return data.getBranchCount() == 1 ? data.getBranch(0) : null;
	}

	/** Descent rate under the stage's first-deploying device(s) only, at ground density. */
	static double drogueOnlyRate(Simulation sim, AxialStage stage, double mass, double rho) {
		List<Sims.Deployment> deps = new ArrayList<>();
		for (Sims.Deployment d : Sims.deployments(sim)) {
			if (d.device().getStage().getID().equals(stage.getID())) {
				deps.add(d);
			}
		}
		if (deps.size() < 2) {
			return Double.NaN;
		}
		deps.sort((a, b) -> Double.compare(a.time(), b.time()));
		double first = deps.get(0).time(), cda = 0;
		for (Sims.Deployment d : deps) {
			if (d.time() <= first + 0.5 && d.device() instanceof Parachute p) {
				cda += p.getCD() * Math.PI * p.getDiameter() * p.getDiameter() / 4;
			}
		}
		return cda > 0 ? Parachutes.descentRate(mass, cda, rho) : Double.NaN;
	}
}
