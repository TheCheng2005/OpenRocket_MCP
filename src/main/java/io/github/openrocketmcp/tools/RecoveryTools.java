package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.calc.Charges;
import io.github.openrocketmcp.calc.OpeningShock;
import io.github.openrocketmcp.calc.Packing;
import io.github.openrocketmcp.calc.Parachutes;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.OrRuntime;
import io.github.openrocketmcp.or.Recovery;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** The recovery chain: loads, shear pins, ejection charges, bay volume, parachute sizing, landing energy. */
public final class RecoveryTools {
	private RecoveryTools() {
	}

	static double pinStrength(Context ctx, Args a) {
		if (a.has("pinStrength")) {
			return a.qty("pinStrength", Dim.FORCE);
		}
		if (a.has("pinType")) {
			double s = ctx.standards().pinStrength(a.str("pinType"));
			if (Double.isNaN(s)) {
				throw new ToolException("Unknown pinType '" + a.str("pinType") + "'. Known: " + ctx.standards().pinNames()
						+ ". Add it under recovery.shearPins with update_standards, or pass pinStrength.");
			}
			return s;
		}
		return Double.NaN;
	}

	static Schema pins(Schema s) {
		return s.str("pinType", "Shear pin name from the team standards (e.g. \"4-40 nylon\").", false)
				.qty("pinStrength", "Shear strength of one pin, e.g. \"140 N\" (overrides pinType).", false);
	}

	/** Air density from explicit density, else altitude MSL via ISA. */
	static double density(Args a, Standards std, double defaultAltitudeMsl) {
		if (a.has("airDensity")) {
			return a.qty("airDensity", Dim.DENSITY);
		}
		double alt = a.qty("altitudeMsl", Dim.DISTANCE, Double.NaN);
		if (Double.isNaN(alt) && a.has("altitudeAgl")) {
			alt = a.qty("altitudeAgl", Dim.DISTANCE) + std.q("launchSite.altitudeMsl", Dim.DISTANCE, 0);
		}
		if (Double.isNaN(alt)) {
			alt = defaultAltitudeMsl;
		}
		return Atmosphere.at(alt).density();
	}

	static Schema atmosphere(Schema s) {
		return s.qty("altitudeMsl", "Altitude above sea level for air density (ISA).", false)
				.qty("altitudeAgl", "Altitude above the launch site (site altitude from team standards).", false)
				.qty("airDensity", "Air density, overrides altitude.", false);
	}

	/** Inner diameter of a tube-like component. */
	static double innerDiameter(RocketComponent c) {
		if (c instanceof BodyTube bt) {
			return bt.getInnerRadius() * 2;
		}
		if (c instanceof InnerTube it) {
			return it.getInnerRadius() * 2;
		}
		if (c instanceof TubeCoupler tc) {
			return tc.getInnerRadius() * 2;
		}
		throw new ToolException("'" + c.getName() + "' is a " + c.getComponentName() + "; give a body tube for the bay.");
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("recovery_analysis", "Recovery chain from a simulated flight",
				"For every recovery deployment in every stage of a simulated flight: airspeed (including horizontal velocity and "
						+ "wind), altitude, air density and mass at deployment; opening load by the infinite-mass Cx method and by a "
						+ "finite-mass inflation simulation; Knacke mass ratio; design load (per team standards); shear pins needed "
						+ "to hold it; steady descent rate and landing energy. Use this instead of hand-estimating deployment speed.",
				pins(SimTools.overrides(SimTools.simSelect(Schema.object())))
						.num("cx", "Override the opening force coefficient Cx.", false).build(),
				false, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					String pinName = a.has("pinType") && !a.has("pinStrength") ? a.str("pinType") : null;
					return Recovery.analyze(sim, ctx.standards(), pinStrength(ctx, a), pinName, a.num("cx", Double.NaN));
				}));

		s.tool(new ToolDef("deployment_delay_sweep", "How late can a device deploy?",
				"Re-simulate with different deployment delays for one recovery device and report airspeed and opening load at "
						+ "each. With pinCount and a pin rating, reports whether the pinned joint holds and the latest delay that "
						+ "keeps the load within capacity / safety factor. Answers 'what if the drogue fires late?' and sizes backup "
						+ "altimeter delays. The design's deployment settings are restored afterwards.",
				pins(SimTools.simSelect(Schema.object()))
						.str("device", "Recovery device id or name.", true)
						.array("delays", "Delays to test, e.g. [0, 1, 2, 3, 4] (s).", Schema.quantityItem(), true)
						.integer("pinCount", "Number of shear pins in the joint that must hold.", false).build(),
				false, a -> delaySweep(ctx, a)));

		s.tool(new ToolDef("size_parachute", "Size a parachute for a descent rate",
				"Required CdA and diameter for a target descent rate, using the descent mass and the air density where the rate "
						+ "matters, and the best matching real parachutes from OpenRocket's parts database (e.g. Fruity Chutes, "
						+ "Rocketman, Top Flight) with their resulting descent rate and packed size. Use deviceId to take the mass "
						+ "from a simulation of the design.",
				atmosphere(SimTools.simSelect(Schema.object()))
						.qty("targetDescentRate", "Target steady descent rate, e.g. \"20 ft/s\".", true)
						.qty("mass", "Mass under the parachute (descent mass after burnout).", false)
						.str("device", "Take the descent mass from the simulated deployment of this device.", false)
						.num("cd", "Drag coefficient referenced to the nominal canopy area (default 0.8 or the device's Cd).", false)
						.integer("count", "Number of identical parachutes sharing the load (default 1).", false)
						.num("tolerance", "Preset match tolerance on descent rate, fraction (default 0.15).", false).build(),
				true, a -> sizeParachute(ctx, a)));

		s.tool(new ToolDef("search_parachutes", "Search parachutes in the parts database",
				"List parachute presets from OpenRocket's parts database with diameter, Cd, CdA and packed size.",
				Schema.object().qty("minDiameter", "Minimum diameter.", false).qty("maxDiameter", "Maximum diameter.", false)
						.str("manufacturer", "Manufacturer substring.", false).integer("limit", "Maximum results (default 40).", false).build(),
				true, a -> {
					List<Map<String, Object>> out = new ArrayList<>();
					for (ComponentPreset p : parachutePresets()) {
						double d = p.has(ComponentPreset.DIAMETER) ? p.get(ComponentPreset.DIAMETER) : Double.NaN;
						if (Double.isNaN(d) || (a.has("minDiameter") && d < a.qty("minDiameter", Dim.LENGTH) - 1e-4)
								|| (a.has("maxDiameter") && d > a.qty("maxDiameter", Dim.LENGTH) + 1e-4)) {
							continue;
						}
						if (a.has("manufacturer") && !p.getManufacturer().getDisplayName().toLowerCase(Locale.ROOT)
								.contains(a.str("manufacturer").toLowerCase(Locale.ROOT))) {
							continue;
						}
						out.add(renderPreset(p, Double.NaN, Double.NaN));
					}
					out.sort((x, y) -> Double.compare((double) x.get("_d"), (double) y.get("_d")));
					out.forEach(m -> m.remove("_d"));
					return out.subList(0, Math.min(a.integer("limit", 40), out.size()));
				}));

		s.tool(new ToolDef("opening_shock", "Parachute opening load",
				"Opening load for given conditions: infinite-mass (Cx * q * CdA, as in Knacke table 5-1), finite-mass inflation "
						+ "simulation, Knacke mass ratio, and steady drag. Include the horizontal airspeed at deployment in velocity. "
						+ "Prefer recovery_analysis when a design is available (it takes conditions from the simulation).",
				atmosphere(Schema.object())
						.qty("mass", "Mass decelerated by the canopy.", true)
						.qty("velocity", "Airspeed at line stretch (total, not just vertical).", true)
						.num("cd", "Drag coefficient (with the matching reference area).", true)
						.qty("area", "Reference area for cd (nominal or projected, whichever cd refers to).", false)
						.qty("diameter", "Canopy diameter (nominal); used for area if area is omitted and for fill time.", false)
						.num("cx", "Opening force coefficient (default from team standards, 1.4).", false)
						.qty("pathAngle", "Flight path angle below horizontal at deployment (default 90 deg = straight down).", false)
						.qty("otherCdA", "CdA of devices already open (e.g. drogue when the main opens).", false)
						.num("fillConstant", "Canopy fill constant n (default from standards).", false)
						.num("inflationExponent", "Inflation curve exponent (default from standards).", false).build(),
				true, a -> {
					Standards std = ctx.standards();
					double rho = density(a, std, std.q("launchSite.altitudeMsl", Dim.DISTANCE, 0));
					double m = a.qty("mass", Dim.MASS);
					double v = a.qty("velocity", Dim.VELOCITY);
					double cd = a.num("cd");
					double diameter = a.qtyOrNaN("diameter", Dim.LENGTH);
					double area = a.has("area") ? a.qty("area", Dim.AREA) : Double.isNaN(diameter) ? Double.NaN : Parachutes.circleArea(diameter);
					if (Double.isNaN(area)) {
						throw new ToolException("Give area or diameter.");
					}
					if (Double.isNaN(diameter)) {
						diameter = Math.sqrt(4 * area / Math.PI);
					}
					double cdA = cd * area;
					double cx = a.num("cx", std.q("recovery.openingForceCoefficient", Dim.DIMENSIONLESS, 1.4));
					OpeningShock.Inflation inf = OpeningShock.inflation(m, cdA, a.qty("otherCdA", Dim.AREA, 0), rho, v,
							a.qty("pathAngle", Dim.ANGLE, Math.PI / 2), diameter,
							a.num("fillConstant", std.q("recovery.canopyFillConstant", Dim.DIMENSIONLESS, 4)),
							a.num("inflationExponent", std.q("recovery.inflationExponent", Dim.DIMENSIONLESS, 1)));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("airDensity", Units.fmt(rho, Dim.DENSITY));
					out.put("cdA", Units.fmt(cdA, Dim.AREA));
					out.put("dynamicPressure", Units.fmt(OpeningShock.dynamicPressure(rho, v), Dim.PRESSURE));
					out.put("infiniteMassLoad", Units.fmt(OpeningShock.infiniteMass(rho, v, cdA, cx), Dim.FORCE) + " (Cx = " + Units.num(cx) + ")");
					out.put("finiteMassLoad", Units.fmt(inf.peakForce(), Dim.FORCE) + " at t=" + Units.num(inf.timeOfPeak()) + " s, peak deceleration "
							+ Units.num(inf.peakDeceleration() / Atmosphere.G0) + " G");
					out.put("fillTime", Units.fmt(inf.fillTime(), Dim.TIME));
					out.put("knackeMassRatio", Units.num(OpeningShock.massRatio(rho, cdA, m)));
					out.put("steadyDescentRate", Units.fmt(Parachutes.descentRate(m, cdA, rho), Dim.VELOCITY));
					out.put("steadyDrag(=weight)", Units.fmt(m * Atmosphere.G0, Dim.FORCE));
					out.put("method", "F_inf = Cx * 0.5 * rho * v^2 * Cd * A; finite mass: integrate m dv/dt = -0.5 rho v^2 CdA(t) + m g with "
							+ "CdA(t) = CdA (t/t_f)^j, t_f = n D0 / v0 (Knacke). Sources: Knacke NWC TP 6575 (DTIC ADA247666).");
					return out;
				}));

		s.tool(new ToolDef("shear_pins", "Shear pins for a load",
				"Number of shear pins needed to hold a joint closed under a load, with the team safety factor, and the resulting "
						+ "force needed to separate it (which the ejection charge must overcome).",
				pins(Schema.object()).qty("holdForce", "Load the joint must hold, e.g. the drogue opening load.", true)
						.num("safetyFactor", "Safety factor (default from standards).", false).build(),
				true, a -> {
					double strength = pinStrength(ctx, a);
					if (Double.isNaN(strength)) {
						throw new ToolException("Give pinType or pinStrength.");
					}
					double sf = a.num("safetyFactor", ctx.standards().q("recovery.shearPinHoldSafetyFactor", Dim.DIMENSIONLESS, 2));
					double f = a.qty("holdForce", Dim.FORCE);
					int n = Charges.shearPinsToHold(f, strength, sf);
					double minPins = ctx.standards().rule("shearPins.recommendedMinimum", Dim.DIMENSIONLESS);
					if (!Double.isNaN(minPins)) {
						n = Math.max(n, (int) minPins);
					}
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("pins", n);
					out.put("capacity", Units.fmt(n * strength, Dim.FORCE));
					out.put("actualSafetyFactor", Units.num(n * strength / f));
					out.put("separationForceForEjection", Units.fmt(n * strength, Dim.FORCE));
					out.put("note", "At least " + (Double.isNaN(minPins) ? 3 : (int) minPins) + " pins, evenly spaced, to avoid cocking (DTEG 8.8.3). "
							+ "Ground test the deployment (R8.8.8).");
					return out;
				}));

		s.tool(new ToolDef("ejection_charge", "Black powder ejection charge",
				"Black powder mass for a recovery bay, by the ideal-gas method (R = 266 in-lbf/lbm-R, T = 3307 R; ~0.006 D^2 L "
						+ "grams at 15 psi). Give the bay by diameter + length, volume, or a body tube component + length. Give either a "
						+ "target pressure, or the shear pins (+ friction/extra force) the charge must break. Returns primary and "
						+ "backup charges, pressure and separating force, and the maximum charge-well volume. Always ground test.",
				pins(Schema.object()).str("designId", DesignTools.DESIGN_ID, false)
						.str("bayTube", "Body tube component whose inner diameter is the bay diameter.", false)
						.qty("bayDiameter", "Bay inner diameter.", false)
						.qty("bayLength", "Free length of the bay being pressurized.", false)
						.qty("bayVolume", "Free volume of the bay (instead of diameter x length).", false)
						.qty("pressure", "Target pressure, e.g. \"15 psi\".", false)
						.integer("pinCount", "Number of shear pins to break.", false)
						.qty("extraForce", "Additional force to overcome (friction, nose cone fit).", false)
						.num("safetyFactor", "Multiplier on the force/pressure (default ejectionForceSafetyFactor).", false)
						.num("backupFactor", "Backup charge multiplier (default backupChargeFactor).", false).build(),
				true, a -> ejection(ctx, a)));

		s.tool(new ToolDef("recovery_bay_fit", "Will the recovery hardware fit?",
				"Packed volume of parachutes, shock cords and other items (cords as width x thickness x length, as in the team's "
						+ "recovery volume sheet), times the packing factor, compared with the bay. Gives the bay length needed.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.array("items", "Items: {name, packedVolume} | {name, packedDiameter, packedLength} | {name, cordLength, "
								+ "cordWidth?, cordThickness?} | {name, preset: \"<manufacturer> <part no>\"}.", Schema.type("object", null), true)
						.str("bayTube", "Body tube component for the bay diameter.", false)
						.str("bayComponent", "Body tube, nose cone or transition whose interior is the bay; its usable "
								+ "volume is computed from the design (tube: inner diameter x availableLength or tube length).", false)
						.qty("bayDiameter", "Bay inner diameter.", false)
						.qty("availableLength", "Usable bay length.", false)
						.qty("availableVolume", "Usable bay volume (e.g. nose cone interior).", false)
						.num("packingFactor", "Multiplier on packed volume (default from standards; see measuredPackingFactors).", false).build(),
				true, a -> bayFit(ctx, a)));

		s.tool(new ToolDef("descent_energy", "Landing kinetic energy per section",
				"Kinetic energy at touchdown for each independently tethered section, and the maximum descent rate that keeps the "
						+ "heaviest section under an energy limit.",
				Schema.object().array("sections", "[{name, mass}] with masses as numbers (kg) or strings with units.", Schema.type("object", null), true)
						.qty("descentRate", "Landing descent rate.", true)
						.qty("energyLimit", "Kinetic energy limit, e.g. \"75 ft-lbf\".", false).build(),
				true, a -> {
					double v = a.qty("descentRate", Dim.VELOCITY);
					double limit = a.qtyOrNaN("energyLimit", Dim.ENERGY);
					List<Map<String, Object>> rows = new ArrayList<>();
					double heaviest = 0;
					for (Args sec : a.objList("sections")) {
						double m = sec.qty("mass", Dim.MASS);
						heaviest = Math.max(heaviest, m);
						double ke = Parachutes.kineticEnergy(m, v);
						Map<String, Object> r = new LinkedHashMap<>();
						r.put("section", sec.str("name", "section"));
						r.put("mass", Units.fmt(m, Dim.MASS));
						r.put("kineticEnergy", Units.fmt(ke, Dim.ENERGY));
						if (!Double.isNaN(limit)) {
							r.put("withinLimit", ke <= limit);
						}
						rows.add(r);
					}
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("sections", rows);
					if (!Double.isNaN(limit) && heaviest > 0) {
						out.put("maxDescentRateForLimit", Units.fmt(Math.sqrt(2 * limit / heaviest), Dim.VELOCITY));
					}
					return out;
				}));
	}

	static List<ComponentPreset> parachutePresets() {
		return OrRuntime.presets().listForType(ComponentPreset.Type.PARACHUTE);
	}

	static Map<String, Object> renderPreset(ComponentPreset p, double mass, double rho) {
		Map<String, Object> m = new LinkedHashMap<>();
		double d = p.get(ComponentPreset.DIAMETER);
		Double cd = p.has(ComponentPreset.CD) ? p.get(ComponentPreset.CD) : null;
		m.put("parachute", p.getManufacturer().getSimpleName() + " " + p.getPartNo());
		if (p.has(ComponentPreset.DESCRIPTION)) {
			m.put("description", p.get(ComponentPreset.DESCRIPTION));
		}
		m.put("diameter", Units.fmt(d, Dim.LENGTH));
		m.put("cd", cd == null ? "not listed (0.8 assumed)" : Units.num(cd));
		double cdA = (cd == null ? 0.8 : cd) * Parachutes.circleArea(d);
		m.put("cdA", Units.fmt(cdA, Dim.AREA));
		if (!Double.isNaN(mass)) {
			m.put("descentRate", Units.fmt(Parachutes.descentRate(mass, cdA, rho), Dim.VELOCITY));
		}
		if (p.has(ComponentPreset.PACKED_DIAMETER) && p.has(ComponentPreset.PACKED_LENGTH)) {
			double pd = p.get(ComponentPreset.PACKED_DIAMETER), pl = p.get(ComponentPreset.PACKED_LENGTH);
			m.put("packed", Units.fmt(pd, Dim.LENGTH) + " dia x " + Units.fmt(pl, Dim.LENGTH) + " = "
					+ Units.fmt(Packing.cylinderVolume(pd, pl), Dim.VOLUME));
		}
		if (p.has(ComponentPreset.MASS)) {
			m.put("mass", Units.fmt(p.get(ComponentPreset.MASS), Dim.MASS));
		}
		m.put("_d", d);
		m.put("_cdA", cdA);
		return m;
	}

	static ComponentPreset findPreset(String ref) {
		String r = ref.toLowerCase(Locale.ROOT).trim();
		for (ComponentPreset p : parachutePresets()) {
			String name = (p.getManufacturer().getSimpleName() + " " + p.getPartNo()).toLowerCase(Locale.ROOT);
			if (name.equals(r) || p.getPartNo().equalsIgnoreCase(r)) {
				return p;
			}
		}
		throw new ToolException("No parachute preset '" + ref + "'. Use search_parachutes.");
	}

	private static Object sizeParachute(Context ctx, Args a) {
		Standards std = ctx.standards();
		double v = a.qty("targetDescentRate", Dim.VELOCITY);
		double mass;
		double rho;
		double cd = a.num("cd", 0.8);
		String massSource;
		if (a.has("device")) {
			Simulation sim = SimTools.runSelected(ctx, a);
			Sims.Deployment dep = null;
			for (Sims.Deployment d : Sims.deployments(sim)) {
				if (Components.shortId(d.device()).equalsIgnoreCase(a.str("device")) || d.device().getName().equalsIgnoreCase(a.str("device"))
						|| d.device().getID().toString().startsWith(a.str("device"))) {
					dep = d;
				}
			}
			if (dep == null) {
				throw new ToolException("Device '" + a.str("device") + "' did not deploy in the simulation.");
			}
			mass = dep.mass();
			if (!a.has("cd")) {
				cd = dep.device().getCD();
			}
			massSource = "simulated mass at deployment of " + dep.device().getName() + " (" + dep.branch() + ")";
			double siteAlt = sim.getOptions().getLaunchAltitude();
			rho = a.has("airDensity") || a.has("altitudeMsl") || a.has("altitudeAgl") ? density(a, std, siteAlt) : Atmosphere.at(siteAlt).density();
		} else {
			mass = a.qty("mass", Dim.MASS);
			massSource = "given";
			rho = density(a, std, std.q("launchSite.altitudeMsl", Dim.DISTANCE, 0));
		}
		int count = Math.max(1, a.integer("count", 1));
		double cdA = Parachutes.requiredCdA(mass, v, rho) / count;
		double diameter = Parachutes.diameterFor(cdA, cd);
		double tol = a.num("tolerance", 0.15);
		List<Map<String, Object>> matches = new ArrayList<>();
		for (ComponentPreset p : parachutePresets()) {
			if (!p.has(ComponentPreset.DIAMETER)) {
				continue;
			}
			Map<String, Object> m = renderPreset(p, mass / count, rho);
			double rate = Parachutes.descentRate(mass / count, (double) m.get("_cdA"), rho);
			if (Math.abs(rate - v) / v <= tol) {
				m.put("_err", Math.abs(rate - v));
				matches.add(m);
			}
		}
		matches.sort((x, y) -> Double.compare((double) x.get("_err"), (double) y.get("_err")));
		matches.forEach(m -> {
			m.remove("_d");
			m.remove("_cdA");
			m.remove("_err");
		});
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("mass", Units.fmt(mass, Dim.MASS) + " (" + massSource + ")");
		out.put("airDensity", Units.fmt(rho, Dim.DENSITY));
		out.put("targetDescentRate", Units.fmt(v, Dim.VELOCITY));
		out.put("requiredCdA" + (count > 1 ? "PerParachute" : ""), Units.fmt(cdA, Dim.AREA));
		out.put("nominalDiameterAtCd" + Units.num(cd), Units.fmt(diameter, Dim.LENGTH));
		out.put("presetMatches", matches.subList(0, Math.min(12, matches.size())));
		out.put("notes", List.of(
				"v = sqrt(2 m g / (rho Cd A)). Cd and A must use the same reference area: OpenRocket and most vendors use the "
						+ "nominal (flat) area; some vendors quote Cd on projected area (e.g. Fruity Chutes Iris Ultra Cd 2.2).",
				"Preset Cd values come from OpenRocket's parts database where listed; otherwise 0.8 is assumed. Verify against "
						+ "the vendor's descent-rate chart.",
				"Descent rate at landing depends on the landing-site density; drogue rates depend on the altitude of interest."));
		return out;
	}

	private static Object delaySweep(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		RecoveryDevice device = Components.find(d.doc.getRocket(), a.str("device"), RecoveryDevice.class, "recovery device");
		Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), ctx.standards(), false);
		FlightConfigurationId fcid = base.getFlightConfigurationId();
		DeploymentConfiguration original = device.getDeploymentConfigurations().get(fcid);
		double strength = pinStrength(ctx, a);
		int pinCount = a.integer("pinCount", 0);
		double sf = ctx.standards().q("recovery.shearPinHoldSafetyFactor", Dim.DIMENSIONLESS, 2);
		double capacity = pinCount > 0 && !Double.isNaN(strength) ? pinCount * strength : Double.NaN;
		List<Double> delays = a.qtyList("delays", Dim.TIME);
		delays.sort(null); // "latest delay within capacity" assumes increasing delays
		String deviceId = device.getID().toString();
		List<Simulation> variants = new ArrayList<>();
		for (double delay : delays) {
			variants.add(Variants.of(base, d.doc, r -> {
				RecoveryDevice rd = (RecoveryDevice) Components.find(r, deviceId);
				DeploymentConfiguration dc = original.copy(fcid);
				dc.setDeployDelay(delay);
				rd.getDeploymentConfigurations().set(fcid, dc);
			}, null));
		}
		List<Variants.Run> runs = Variants.runAll(variants);
		List<Map<String, Object>> rows = new ArrayList<>();
		double latestOk = Double.NaN;
		boolean stillOk = true;
		for (int i = 0; i < runs.size(); i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("delay", Units.fmt(delays.get(i), Dim.TIME));
			Sims.Deployment dep = null;
			if (runs.get(i).ok()) {
				for (Sims.Deployment x : Sims.deployments(runs.get(i).sim())) {
					if (x.device().getID().toString().equals(deviceId)) {
						dep = x;
						break;
					}
				}
			} else {
				row.put("error", runs.get(i).error());
			}
			if (dep == null) {
				row.putIfAbsent("result", "did not deploy");
				stillOk = false;
				rows.add(row);
				continue;
			}
			Recovery.Loads l = Recovery.loads(dep, 0, ctx.standards(), Double.NaN);
			row.put("altitudeAGL", Units.fmt(dep.altitudeAgl(), Dim.DISTANCE));
			row.put("airspeed", Units.fmt(dep.airspeed(), Dim.VELOCITY));
			row.put("openingLoadInfiniteMass", Units.fmt(l.infiniteMass(), Dim.FORCE));
			row.put("openingLoadFiniteMass", Units.fmt(l.finiteMass(), Dim.FORCE));
			row.put("designLoad", Units.fmt(l.design(), Dim.FORCE));
			if (!Double.isNaN(capacity)) {
				boolean ok = l.design() * sf <= capacity;
				row.put("pinsHold", ok ? "yes" : "NO");
				if (ok && stillOk) {
					latestOk = delays.get(i);
				} else {
					stillOk = false;
				}
			}
			rows.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("device", device.getName());
		out.put("baseDeployment", original.getDeployEvent().name() + " + delay");
		out.put("results", rows);
		if (!Double.isNaN(capacity)) {
			out.put("jointCapacity", pinCount + " pins = " + Units.fmt(capacity, Dim.FORCE) + " (safety factor " + Units.num(sf) + ")");
			out.put("latestDelayWithinCapacity", Double.isNaN(latestOk) ? "none of the tested delays" : Units.fmt(latestOk, Dim.TIME));
		}
		return out;
	}

	private static Object ejection(Context ctx, Args a) {
		Standards std = ctx.standards();
		double diameter = a.qtyOrNaN("bayDiameter", Dim.LENGTH);
		if (a.has("bayTube")) {
			Designs.Design d = ctx.designs.get(a.str("designId", null));
			diameter = innerDiameter(Components.find(d.doc.getRocket(), a.str("bayTube")));
		}
		double volume = a.qtyOrNaN("bayVolume", Dim.VOLUME);
		if (Double.isNaN(volume)) {
			if (Double.isNaN(diameter) || !a.has("bayLength")) {
				throw new ToolException("Give bayVolume, or bayDiameter/bayTube and bayLength.");
			}
			volume = Packing.cylinderVolume(diameter, a.qty("bayLength", Dim.LENGTH));
		}
		double sf = a.num("safetyFactor", std.q("recovery.ejectionForceSafetyFactor", Dim.DIMENSIONLESS, 1.5));
		double pressure;
		String basis;
		if (a.has("pressure")) {
			pressure = a.qty("pressure", Dim.PRESSURE) * sf;
			basis = "target pressure x safety factor " + Units.num(sf);
		} else {
			if (Double.isNaN(diameter)) {
				throw new ToolException("A bay diameter (bayDiameter or bayTube) is needed to turn pin force into pressure.");
			}
			double strength = pinStrength(ctx, a);
			int pins = a.integer("pinCount", 0);
			double extra = a.qty("extraForce", Dim.FORCE, 0);
			if ((pins == 0 || Double.isNaN(strength)) && extra == 0) {
				throw new ToolException("Give pressure, or pinCount with pinType/pinStrength (and optionally extraForce).");
			}
			double force = (pins > 0 ? pins * strength : 0) + extra;
			pressure = Charges.pressureForForce(force * sf, diameter);
			basis = "break " + pins + " pins (" + Units.fmt(force, Dim.FORCE) + " incl. extra force) x safety factor " + Units.num(sf);
		}
		double grams = Charges.blackPowderGrams(pressure, volume);
		double backup = grams * a.num("backupFactor", std.q("recovery.backupChargeFactor", Dim.DIMENSIONLESS, 1.25));
		double bulk = std.q("recovery.blackPowderBulkDensity", Dim.DENSITY, 1000);
		double wellRatio = std.rule("blackPowder.chargeWellMaxVolumeRatio", Dim.DIMENSIONLESS);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bayVolume", Units.fmt(volume, Dim.VOLUME));
		if (!Double.isNaN(diameter)) {
			out.put("bayDiameter", Units.fmt(diameter, Dim.LENGTH));
		}
		out.put("designPressure", Units.fmt(pressure, Dim.PRESSURE) + " (" + basis + ")");
		if (!Double.isNaN(diameter)) {
			out.put("separatingForce", Units.fmt(Charges.forceFromPressure(pressure, diameter), Dim.FORCE));
		}
		out.put("primaryCharge", Units.fmt(grams / 1000, Dim.CHARGE_MASS));
		out.put("backupCharge", Units.fmt(backup / 1000, Dim.CHARGE_MASS));
		if (!Double.isNaN(wellRatio)) {
			out.put("maxChargeWellVolume(backup)", Units.fmt(wellRatio * backup / 1000 / bulk, Dim.VOLUME)
					+ " (<= " + Units.num(wellRatio) + "x charge volume, DTEG 8.8.4; bulk density "
					+ Units.fmt(bulk, Dim.DENSITY) + " assumed)");
		}
		List<String> notes = new ArrayList<>();
		notes.add("Ideal-gas estimate: grams = P[psi] * V[in^3] / (266 * 3307) * 453.6. Ground test to confirm (DTEG R4.11, R8.8.8).");
		if (grams < 1) {
			notes.add("Charges under 1 g are rarely appropriate for competition-size bays (DTEG 8.8.4); check the bay volume and inputs.");
		}
		notes.add("At high altitude, low ambient pressure and cold slow BP combustion: seal the charge (DTEG 8.8.4) and verify by vacuum-chamber or ground test.");
		out.put("notes", notes);
		return out;
	}

	private static Object bayFit(Context ctx, Args a) {
		Standards std = ctx.standards();
		double cordW = std.q("recovery.shockCord.width", Dim.LENGTH, 0.01905);
		double cordT = std.q("recovery.shockCord.thickness", Dim.LENGTH, 0.00635);
		double total = 0;
		List<Map<String, Object>> items = new ArrayList<>();
		for (Args it : a.objList("items")) {
			double vol;
			String how;
			if (it.has("packedVolume")) {
				vol = it.qty("packedVolume", Dim.VOLUME);
				how = "given";
			} else if (it.has("preset")) {
				ComponentPreset p = findPreset(it.str("preset"));
				if (!p.has(ComponentPreset.PACKED_DIAMETER) || !p.has(ComponentPreset.PACKED_LENGTH)) {
					throw new ToolException("Preset " + it.str("preset") + " has no packed size; give packedVolume from the vendor.");
				}
				vol = Packing.cylinderVolume(p.get(ComponentPreset.PACKED_DIAMETER), p.get(ComponentPreset.PACKED_LENGTH));
				how = "parts database packed size";
			} else if (it.has("packedDiameter") && it.has("packedLength")) {
				vol = Packing.cylinderVolume(it.qty("packedDiameter", Dim.LENGTH), it.qty("packedLength", Dim.LENGTH));
				how = "cylinder";
			} else if (it.has("cordLength")) {
				double w = it.qty("cordWidth", Dim.LENGTH, cordW), t = it.qty("cordThickness", Dim.LENGTH, cordT);
				vol = Packing.cordVolume(w, t, it.qty("cordLength", Dim.LENGTH));
				how = Units.fmt(w, Dim.LENGTH) + " x " + Units.fmt(t, Dim.LENGTH) + " x length";
			} else {
				throw new ToolException("Each item needs packedVolume, preset, packedDiameter+packedLength, or cordLength.");
			}
			total += vol;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("item", it.str("name", "item"));
			m.put("volume", Units.fmt(vol, Dim.VOLUME));
			m.put("basis", how);
			items.add(m);
		}
		double pf = a.num("packingFactor", std.q("recovery.packingFactor", Dim.DIMENSIONLESS, 2));
		double required = total * pf;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("packedVolume", Units.fmt(total, Dim.VOLUME));
		out.put("packingFactor", Units.num(pf));
		out.put("requiredVolume", Units.fmt(required, Dim.VOLUME));
		double diameter = a.qtyOrNaN("bayDiameter", Dim.LENGTH);
		if (a.has("bayTube")) {
			Designs.Design d = ctx.designs.get(a.str("designId", null));
			diameter = innerDiameter(Components.find(d.doc.getRocket(), a.str("bayTube")));
		}
		double available = a.qtyOrNaN("availableVolume", Dim.VOLUME);
		if (a.has("bayComponent")) {
			Designs.Design d = ctx.designs.get(a.str("designId", null));
			RocketComponent bay = Components.find(d.doc.getRocket(), a.str("bayComponent"));
			available = Components.interiorVolume(bay, a.qtyOrNaN("availableLength", Dim.LENGTH));
			out.put("bayComponent", bay.getName() + " (" + bay.getComponentName() + ")");
			if (bay instanceof BodyTube bt && Double.isNaN(diameter)) {
				diameter = bt.getInnerRadius() * 2;
			}
		}
		if (!Double.isNaN(diameter)) {
			out.put("bayDiameter", Units.fmt(diameter, Dim.LENGTH));
			out.put("packedLengthNeeded", Units.fmt(Packing.lengthFor(total, diameter), Dim.LENGTH));
			out.put("bayLengthNeeded", Units.fmt(Packing.lengthFor(required, diameter), Dim.LENGTH));
			if (a.has("availableLength") && !a.has("bayComponent")) {
				available = Packing.cylinderVolume(diameter, a.qty("availableLength", Dim.LENGTH));
			}
		}
		if (!Double.isNaN(available)) {
			out.put("availableVolume", Units.fmt(available, Dim.VOLUME));
			out.put("fits", available >= required ? "yes, margin " + Units.num(available / total) + "x packed volume"
					: "NO: only " + Units.num(available / total) + "x packed volume (need " + Units.num(pf) + "x)");
		}
		JsonElement measured = std.data().getAsJsonObject("recovery").get("measuredPackingFactors");
		if (measured != null) {
			out.put("teamMeasuredPackingFactors", measured);
		}
		return out;
	}
}
