package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.masscalc.RigidBody;
import info.openrocket.core.material.Material;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.Bulkhead;
import info.openrocket.core.rocketcomponent.CenteringRing;
import info.openrocket.core.rocketcomponent.EngineBlock;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.InstanceContext;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Rail button placement. The rocket is guided until the aft button leaves the top of the rail, so the aft button's
 * height above the rocket's aft end costs rail length (OpenRocket 24.12 counts that loss for launch lugs but not for
 * rail buttons, so its rail exit velocity is optimistic). Between the forward button leaving and the aft one leaving
 * the rocket hangs on one button and pivots about it (tip-off), pushed by gravity across the tilted rail and by the
 * crosswind on the CP:
 * <pre>
 *   M = m g sin(theta) (x_aft - x_cg) + q S CNa alpha (x_aft - x_cp),   alpha = atan(w / v),  I_p = I_cg + m (x_aft - x_cg)^2
 *   tip-off rate = M / I_p * dt,   tip-off angle = M / (2 I_p) * dt^2,   dt = time for the button spacing s to pass the rail top
 * </pre>
 * The pointing error at rail exit is the tip-off angle plus the slop of the buttons in the rail slot, clearance / s.
 * A short spacing cuts tip-off (dt ~ s), a long one cuts slop, so the forward button goes where their sum is least.
 * With both buttons on the rail, the same side forces are carried as a couple: R_fwd = M / s, R_aft = F - R_fwd.
 */
public final class RailButtons {
	private RailButtons() {
	}

	/** A place a button can go: along a body tube of the main stack, with or without internal backing. */
	record Station(double x0, double x1, BodyTube tube) {
	}

	/** Something inside a tube a button screw can bite into (centering ring, bulkhead, coupler). */
	record Backing(double x0, double x1, String name) {
	}

	/** One layout: button centres (m from the nose tip) and what they give. */
	public record Layout(double xFwd, double xAft, double railExitVelocity, double guidedTravel, double singleButtonTime,
			double tipOffRate, double tipOffAngle, double slopAngle, double fwdLoad, double aftLoad, String fwdBacking,
			String aftBacking) {
		double pointingError() {
			return tipOffAngle + slopAngle;
		}
	}

	/** Inputs that are not in the design. */
	public record Options(double standoff, double clearance, double windSpeed, double minSpacing, double railExitMin) {
	}

	static final class Model {
		final FlightConfiguration fc;
		final double tail, diameter, railLength, angle, mass, xcg, icg, xcp, cna, refArea, rho, wind;
		final double[] travel, time, speed;
		final List<Station> stations = new ArrayList<>();
		final List<Backing> backings = new ArrayList<>();
		final double railExitOr;
		final Options o;

		Model(Simulation sim, Options o) {
			this.o = o;
			fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
			Analysis.settle(fc);
			tail = fc.getBoundingBoxAerodynamic().max.x;
			diameter = Analysis.maxDiameter(fc);
			railLength = sim.getOptions().getLaunchRodLength();
			angle = sim.getOptions().getLaunchRodAngle();
			RigidBody launch = MassCalculator.calculateLaunch(fc);
			mass = launch.getMass();
			xcg = launch.getCM().x;
			icg = launch.getLongitudinalInertia();
			double mach = 0.1;
			Dynamics.Aero aero = Dynamics.aero(fc, mach);
			double sum = 0, mom = 0;
			for (int i = 0; i < aero.cna().length; i++) {
				sum += aero.cna()[i];
				mom += aero.cna()[i] * aero.x()[i];
			}
			cna = sum;
			xcp = sum > 0 ? mom / sum : Double.NaN;
			refArea = aero.refArea();
			rho = Atmosphere.at(sim.getOptions().getLaunchAltitude()).density();
			wind = o.windSpeed();
			railExitOr = sim.getSimulatedData().getLaunchRodVelocity();
			FlightDataBranch b = sim.getSimulatedData().getBranch(0);
			Branch br = Branch.of(b);
			double[] alt = br.col(FlightDataType.TYPE_ALTITUDE), v = br.col(FlightDataType.TYPE_VELOCITY_TOTAL);
			List<double[]> rows = new ArrayList<>();
			double cos = Math.cos(angle);
			for (int i = 0; i < br.size(); i++) {
				double s = alt[i] / cos;
				if (Double.isNaN(s) || Double.isNaN(v[i])) {
					continue;
				}
				if (!rows.isEmpty() && s <= rows.get(rows.size() - 1)[0]) {
					continue; // on the pad, or a repeated sample
				}
				rows.add(new double[] { s, br.time[i], v[i] });
				if (s > railLength * 1.2 + 1) {
					break;
				}
			}
			if (rows.size() < 3 || rows.get(rows.size() - 1)[0] < railLength * 0.5) {
				throw new ToolException("The simulation never climbs the rail (no thrust, or too heavy); fix that first "
						+ "(run_simulation).");
			}
			travel = new double[rows.size()];
			time = new double[rows.size()];
			speed = new double[rows.size()];
			for (int i = 0; i < rows.size(); i++) {
				travel[i] = rows.get(i)[0];
				time[i] = rows.get(i)[1];
				speed[i] = rows.get(i)[2];
			}
			for (RocketComponent c : fc.getRocket()) {
				if (!fc.isComponentActive(c)) {
					continue;
				}
				if (c instanceof BodyTube bt && bt.getParent() instanceof AxialStage) {
					double x0 = bt.toAbsolute(Coordinate.NUL)[0].x;
					stations.add(new Station(x0, x0 + bt.getLength(), bt));
				} else if (c instanceof CenteringRing || c instanceof Bulkhead || c instanceof TubeCoupler || c instanceof EngineBlock) {
					double x0 = c.toAbsolute(Coordinate.NUL)[0].x;
					backings.add(new Backing(x0, x0 + c.getLength(), c.getName()));
				}
			}
			stations.sort(Comparator.comparingDouble(Station::x0));
			if (stations.isEmpty()) {
				throw new ToolException("Rail buttons go on a body tube and the design has none.");
			}
		}

		/** Interpolated {time, speed} at a travel along the rail. */
		double[] at(double s) {
			if (s <= travel[0]) {
				return new double[] { time[0], speed[0] };
			}
			for (int i = 1; i < travel.length; i++) {
				if (travel[i] >= s) {
					double f = (s - travel[i - 1]) / (travel[i] - travel[i - 1]);
					return new double[] { time[i - 1] + f * (time[i] - time[i - 1]), speed[i - 1] + f * (speed[i] - speed[i - 1]) };
				}
			}
			return new double[] { time[time.length - 1], speed[speed.length - 1] };
		}

		/** Side force of the crosswind at a speed along the rail (N). */
		double windForce(double v) {
			if (wind <= 0 || !(cna > 0)) {
				return 0;
			}
			double alpha = Math.atan2(wind, Math.max(v, 0.1));
			return 0.5 * rho * (v * v + wind * wind) * refArea * cna * alpha;
		}

		Layout evaluate(double xFwd, double xAft) {
			double s = xAft - xFwd;
			double guided = railLength - o.standoff() - (tail - xAft);
			double fwdOff = railLength - o.standoff() - (tail - xFwd);
			double[] aft = at(guided), fwd = at(Math.max(0, fwdOff));
			double dt = Math.max(0, aft[0] - fwd[0]);
			double dcg = xAft - xcg, dcp = Double.isNaN(xcp) ? 0 : xAft - xcp;
			double gravity = mass * Atmosphere.G0 * Math.sin(angle);
			double vMid = 0.5 * (aft[1] + fwd[1]);
			double moment = gravity * dcg + windForce(vMid) * dcp;
			double ip = icg + mass * dcg * dcg;
			double acc = moment / ip;
			// Both buttons on: the side forces at the last instant (fastest, so the largest wind force).
			double n = windForce(fwd[1]);
			double rf = (gravity * dcg + n * dcp) / s;
			double ra = gravity + n - rf;
			return new Layout(xFwd, xAft, aft[1], guided, dt, acc * dt, 0.5 * acc * dt * dt, o.clearance() / s, rf, ra,
					backing(xFwd), backing(xAft));
		}

		String backing(double x) {
			for (Backing b : backings) {
				if (x >= b.x0() - 0.003 && x <= b.x1() + 0.003) {
					return b.name();
				}
			}
			return null;
		}

		Station stationAt(double x) {
			for (Station st : stations) {
				if (x >= st.x0() - 1e-6 && x <= st.x1() + 1e-6) {
					return st;
				}
			}
			return null;
		}

		/** Margin from a tube end: the button's own footprint plus a little edge distance. */
		double margin() {
			return Math.max(0.015, 0.1 * diameter);
		}
	}

	/** Existing rail button centres, fore to aft (m from the nose tip). */
	static List<double[]> existing(FlightConfiguration fc) {
		List<double[]> out = new ArrayList<>();
		for (RocketComponent c : fc.getRocket()) {
			if (c instanceof RailButton && fc.isComponentActive(c)) {
				for (InstanceContext ic : fc.getActiveInstances().getInstanceContexts(c)) {
					out.add(new double[] { ic.getLocation().x });
				}
			}
		}
		out.sort(Comparator.comparingDouble(v -> v[0]));
		return out;
	}

	/** The recommended layout: the aft button as far aft as a body tube allows, the forward one where pointing error is least. */
	static Layout optimise(Model m, double currentAft) {
		double margin = m.margin();
		Station aftTube = m.stations.get(m.stations.size() - 1);
		double xAft = aftTube.x1() - margin;
		// Over a ring or bulkhead near the tube's aft end when there is one (the screw needs something to bite).
		double bestBacked = Double.NaN;
		for (Backing b : m.backings) {
			double mid = 0.5 * (b.x0() + b.x1());
			if (mid >= aftTube.x0() + margin && mid <= xAft + 1e-9 && xAft - mid <= 1.0 * m.diameter
					&& (Double.isNaN(bestBacked) || mid > bestBacked)) {
				bestBacked = mid;
			}
		}
		if (!Double.isNaN(bestBacked)) {
			xAft = bestBacked;
		}
		// A button the team already has lower down on the same tube stays: moving it up would only lose rail.
		if (!Double.isNaN(currentAft) && currentAft > xAft && currentAft <= aftTube.x1() - 0.005) {
			xAft = currentAft;
		}
		double minSpacing = Math.max(m.o.minSpacing(), m.diameter);
		double railTop = m.railLength - m.o.standoff();
		Layout best = null;
		for (Station st : m.stations) {
			for (double x = st.x0() + margin; x <= Math.min(st.x1() - margin, xAft - minSpacing) + 1e-9; x += 0.005) {
				if (m.tail - x >= railTop) {
					continue; // above the top of the rail before launch
				}
				Layout l = m.evaluate(x, xAft);
				if (best == null || l.pointingError() < best.pointingError() - 1e-12) {
					best = l;
				}
			}
		}
		if (best == null) {
			throw new ToolException("There is no room for two buttons at least " + Units.fmt(minSpacing, Dim.LENGTH)
					+ " apart on the body tubes.");
		}
		// Snap to a backed station when one is within half a calibre and costs little (<= 10% more pointing error).
		Layout snapped = best;
		for (Backing b : m.backings) {
			double mid = 0.5 * (b.x0() + b.x1());
			if (Math.abs(mid - best.xFwd()) <= 0.5 * m.diameter && xAft - mid >= minSpacing && m.stationAt(mid) != null
					&& m.tail - mid < railTop) {
				Layout l = m.evaluate(mid, xAft);
				if (l.pointingError() <= best.pointingError() * 1.1 && (snapped == best || l.pointingError() < snapped.pointingError())) {
					snapped = l;
				}
			}
		}
		return snapped;
	}

	public static Map<String, Object> analyse(Simulation sim, Options o, boolean apply, double[] manual) {
		Model m = new Model(sim, o);
		Map<String, Object> out = new LinkedHashMap<>();
		List<String> notes = new ArrayList<>();
		Map<String, Object> setup = new LinkedHashMap<>();
		setup.put("rail", Units.fmt(m.railLength, Dim.LENGTH) + " at " + Units.fmt(m.angle, Dim.ANGLE) + " from vertical"
				+ (o.standoff() > 0 ? ", rocket's aft end " + Units.fmt(o.standoff(), Dim.LENGTH) + " above the rail's foot" : ""));
		setup.put("rocket", Units.fmt(m.tail, Dim.LENGTH) + " long, " + Units.fmt(m.mass, Dim.MASS) + " at launch, CG "
				+ Units.fmt(m.tail - m.xcg, Dim.LENGTH) + " above the aft end"
				+ (Double.isNaN(m.xcp) ? "" : ", CP " + Units.fmt(m.tail - m.xcp, Dim.LENGTH) + " above the aft end"));
		setup.put("crosswind", Units.fmt(o.windSpeed(), Dim.VELOCITY) + " (worst direction)");
		setup.put("buttonClearance", Units.fmt(o.clearance(), Dim.LENGTH) + " play in the rail slot");
		out.put("setup", setup);

		List<double[]> have = existing(m.fc);
		Layout current = null;
		if (have.size() >= 2) {
			current = m.evaluate(have.get(0)[0], have.get(have.size() - 1)[0]);
			out.put("current", render(m, current));
			if (have.size() > 2) {
				notes.add(have.size() + " buttons: the foremost and aftmost ones guide the rocket; the others only share load.");
			}
		} else if (have.size() == 1) {
			notes.add("The design has one rail button; a rocket needs two (or more) on the rail.");
		} else {
			boolean lug = false;
			for (RocketComponent c : m.fc.getRocket()) {
				lug |= c instanceof LaunchLug && m.fc.isComponentActive(c);
			}
			notes.add(lug ? "The design uses launch lugs; high-power rails (1010 / 1515) need rail buttons, laid out below."
					: "The design has no rail buttons yet; apply=true adds a pair where recommended.");
		}
		Layout rec;
		if (manual != null) {
			double f = Math.min(manual[0], manual[1]), a = Math.max(manual[0], manual[1]);
			if (m.stationAt(f) == null || m.stationAt(a) == null) {
				throw new ToolException("Both buttons must sit on a body tube of the main airframe (not the nose cone or a "
						+ "transition).");
			}
			rec = m.evaluate(f, a);
			out.put("proposed", render(m, rec));
		} else {
			rec = optimise(m, current == null ? Double.NaN : current.xAft());
			out.put("recommended", render(m, rec));
		}
		if (current != null) {
			Map<String, Object> gain = new LinkedHashMap<>();
			gain.put("railExitVelocity", signed(rec.railExitVelocity() - current.railExitVelocity(), Dim.VELOCITY));
			gain.put("guidedTravel", signed(rec.guidedTravel() - current.guidedTravel(), Dim.LENGTH));
			gain.put("pointingErrorAtRailExit", Units.num(Math.toDegrees(current.pointingError())) + " -> "
					+ Units.num(Math.toDegrees(rec.pointingError())) + " deg");
			out.put(manual != null ? "proposedVsCurrent" : "recommendedVsCurrent", gain);
		}
		out.put("openRocketRailExit", Units.fmt(m.railExitOr, Dim.VELOCITY) + ": OpenRocket counts the whole rail and ignores "
				+ "where the buttons are; the aft button's height above the aft end is rail the rocket never uses.");
		if (!Double.isNaN(o.railExitMin()) && rec.railExitVelocity() < o.railExitMin()) {
			notes.add("Rail exit is below the " + Units.fmt(o.railExitMin(), Dim.VELOCITY) + " minimum even with this layout: "
					+ "more initial thrust, less mass or a longer rail.");
		}
		if (rec.aftBacking() == null) {
			Station aftTube = m.stationAt(rec.xAft());
			for (Backing b : m.backings) {
				if (b.x1() > aftTube.x0() && b.x0() < aftTube.x1() && rec.xAft() - b.x1() < 1.5 * m.diameter) {
					double off = 0.5 * (b.x0() + b.x1()) - rec.xAft();
					notes.add("'" + b.name() + "' is " + Units.fmt(Math.abs(off), Dim.LENGTH) + (off > 0 ? " aft of" : " forward of")
							+ " the aft button: move it under the button, or glue a block there, so the screw bites into it.");
					break;
				}
			}
		}
		if (rec.fwdBacking() == null || rec.aftBacking() == null) {
			notes.add("Put a backing block or ring under every button without one (glued inside the tube), or the screw only "
					+ "holds in the tube wall.");
		}
		double cgAbove = rec.xFwd() - m.xcg;
		notes.add("The forward button is " + Units.fmt(Math.abs(cgAbove), Dim.LENGTH) + (cgAbove > 0 ? " aft of" : " forward of")
				+ " the launch CG. Buttons in line with each other, Delrin (POM), and check the rocket slides the full rail on the pad.");
		if (m.stations.size() > 1 && m.stationAt(rec.xFwd()).tube() != m.stationAt(rec.xAft()).tube()) {
			notes.add("The buttons are on different tubes: line the tubes up (index marks) at assembly so the buttons stay in line.");
		}
		out.put("notes", notes);
		if (apply) {
			out.put("applied", place(m, rec.xFwd(), rec.xAft()));
		}
		out.put("model", "Rail exit when the aft button passes the rail top; tip-off from gravity across the tilted rail and the "
				+ "crosswind on the CP while only the aft button is on (pivot); slop = clearance / spacing. Speeds along the rail "
				+ "come from this simulation.");
		return out;
	}

	private static String signed(double v, Dim d) {
		return (v >= 0 ? "+" : "-") + Units.fmt(Math.abs(v), d);
	}

	static Map<String, Object> render(Model m, Layout l) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("forwardButton", where(m, l.xFwd()) + (l.fwdBacking() == null ? " (no backing: add a block)" : " (over " + l.fwdBacking() + ")"));
		r.put("aftButton", where(m, l.xAft()) + (l.aftBacking() == null ? " (no backing: add a block)" : " (over " + l.aftBacking() + ")"));
		r.put("spacing", Units.fmt(l.xAft() - l.xFwd(), Dim.LENGTH));
		r.put("guidedTravel", Units.fmt(l.guidedTravel(), Dim.LENGTH) + " of " + Units.fmt(m.railLength, Dim.LENGTH) + " rail");
		r.put("railExitVelocity", Units.fmt(l.railExitVelocity(), Dim.VELOCITY));
		r.put("tipOff", Units.num(Math.toDegrees(l.tipOffRate())) + " deg/s pitch rate, " + Units.num(Math.toDegrees(l.tipOffAngle()))
				+ " deg, over " + Units.num(l.singleButtonTime() * 1000) + " ms on the aft button alone");
		r.put("slop", Units.num(Math.toDegrees(l.slopAngle())) + " deg");
		r.put("pointingErrorAtRailExit", Units.num(Math.toDegrees(l.pointingError())) + " deg (tip-off + slop)");
		r.put("buttonLoads", "forward " + Units.fmt(Math.abs(l.fwdLoad()), Dim.FORCE) + ", aft " + Units.fmt(Math.abs(l.aftLoad()), Dim.FORCE)
				+ " sideways (weight across the tilted rail and the crosswind, both buttons on)");
		return r;
	}

	static String where(RailButtons.Model m, double x) {
		return Units.fmt(m.tail - x, Dim.LENGTH) + " above the aft end (" + Units.fmt(x, Dim.LENGTH) + " from the nose tip)";
	}

	/** Moves the design's rail buttons (or adds a pair) so their centres are at xFwd and xAft. */
	static List<String> place(Model m, double xFwd, double xAft) {
		List<String> done = new ArrayList<>();
		List<RailButton> buttons = new ArrayList<>();
		for (RocketComponent c : m.fc.getRocket()) {
			if (c instanceof RailButton rb && m.fc.isComponentActive(c)) {
				buttons.add(rb);
			}
		}
		BodyTube fTube = m.stationAt(xFwd).tube(), aTube = m.stationAt(xAft).tube();
		RailButton template = buttons.isEmpty() ? null : buttons.get(0);
		// Remove extras beyond what the layout needs, keep the first as the style to copy.
		if (fTube == aTube) {
			RailButton rb = template != null ? template : newButton(fTube);
			for (RailButton other : buttons) {
				if (other != rb) {
					other.getParent().removeChild(other);
					done.add("removed " + other.getName());
				}
			}
			reparent(rb, fTube);
			rb.setInstanceCount(2);
			rb.setInstanceSeparation(xAft - xFwd);
			locate(m, rb, xFwd);
			done.add(rb.getName() + ": 2 buttons on " + fTube.getName() + ", " + Units.fmt(xAft - xFwd, Dim.LENGTH) + " apart");
		} else {
			RailButton f = template != null ? template : newButton(fTube);
			RailButton a = buttons.size() > 1 ? buttons.get(1) : copyOf(f, aTube);
			for (RailButton other : buttons) {
				if (other != f && other != a) {
					other.getParent().removeChild(other);
					done.add("removed " + other.getName());
				}
			}
			for (RailButton rb : List.of(f, a)) {
				rb.setInstanceCount(1);
			}
			reparent(f, fTube);
			reparent(a, aTube);
			locate(m, f, xFwd);
			locate(m, a, xAft);
			done.add(f.getName() + " on " + fTube.getName() + "; " + a.getName() + " on " + aTube.getName());
		}
		Analysis.settle(m.fc);
		return done;
	}

	private static RailButton newButton(BodyTube parent) {
		RailButton rb = new RailButton();
		rb.setName("Rail button");
		try {
			rb.setMaterial(Components.material(parent, Material.Type.BULK, "Delrin"));
		} catch (IllegalArgumentException e) {
			// keep OpenRocket's default material
		}
		parent.addChild(rb);
		return rb;
	}

	private static RailButton copyOf(RailButton f, BodyTube parent) {
		RailButton a = (RailButton) f.copy();
		a.setName(f.getName() + " (aft)");
		parent.addChild(a);
		return a;
	}

	private static void reparent(RailButton rb, BodyTube tube) {
		if (rb.getParent() != tube) {
			rb.getParent().removeChild(rb);
			tube.addChild(rb);
		}
	}

	/** Positions the component so its foremost instance centre is at x (measured back from the design, so it is exact). */
	private static void locate(Model m, RailButton rb, double x) {
		rb.setAxialMethod(AxialMethod.TOP);
		double tubeTop = rb.getParent().toAbsolute(Coordinate.NUL)[0].x;
		rb.setAxialOffset(x - tubeTop);
		for (int i = 0; i < 3; i++) {
			double fore = Double.POSITIVE_INFINITY;
			for (InstanceContext ic : m.fc.getActiveInstances().getInstanceContexts(rb)) {
				fore = Math.min(fore, ic.getLocation().x);
			}
			double err = fore - x;
			if (Math.abs(err) < 1e-5) {
				break;
			}
			rb.setAxialOffset(rb.getAxialOffset() - err);
		}
	}
}
