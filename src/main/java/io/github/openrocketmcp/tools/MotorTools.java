package io.github.openrocketmcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.IgnitionEvent;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Motors;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Motor search, selection, ranking and custom engines. */
public final class MotorTools {
	private MotorTools() {
	}

	private static Schema filterSchema(Schema s) {
		return s.qty("minDiameter", "Minimum motor diameter, e.g. \"75 mm\".", false)
				.qty("maxDiameter", "Maximum motor diameter.", false)
				.qty("maxLength", "Maximum motor length.", false)
				.str("minClass", "Lowest impulse class letter, e.g. \"K\".", false)
				.str("maxClass", "Highest impulse class letter, e.g. \"M\".", false)
				.str("certLevel", "Certification level L1/L2/L3: caps the impulse class (L1 = H-I, L2 = J-L, L3 = M-O).", false)
				.str("manufacturer", "Manufacturer name or abbreviation (e.g. \"CTI\", \"AeroTech\").", false)
				.str("designationContains", "Substring of the designation, e.g. \"M1850\".", false)
				.enumStr("type", "Motor type.", false, "single", "reload", "hybrid", "unknown")
				.bool("availableOnly", "Only motors currently in production (default true).", false);
	}

	private static Motors.Filter filter(Args a, double mountDiameter) {
		String maxClass = a.str("maxClass", null);
		if (a.has("certLevel")) {
			String cap = Motors.certMaxClass(a.str("certLevel"));
			maxClass = maxClass == null || maxClass.compareToIgnoreCase(cap) > 0 ? cap : maxClass;
		}
		double maxD = a.qty("maxDiameter", Dim.LENGTH, mountDiameter);
		double minD = a.qty("minDiameter", Dim.LENGTH, Double.isNaN(mountDiameter) ? Double.NaN : mountDiameter - 0.001);
		return new Motors.Filter(minD, maxD, a.qtyOrNaN("maxLength", Dim.LENGTH), a.str("minClass", null), maxClass,
				a.str("manufacturer", null), a.str("designationContains", null), a.str("type", null), a.bool("availableOnly", true));
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("search_motors", "Search the motor database",
				"Search OpenRocket's built-in motor database (plus imported/custom motors) by diameter, length, impulse class, "
						+ "certification level, manufacturer or designation. For simulated ranking in a design, use rank_motors.",
				filterSchema(Schema.object()).integer("limit", "Maximum results (default 40).", false).build(), true, a -> {
					List<ThrustCurveMotor> found = Motors.search(filter(a, Double.NaN));
					int limit = a.integer("limit", 40);
					List<Map<String, Object>> out = new ArrayList<>();
					for (ThrustCurveMotor m : found.subList(0, Math.min(limit, found.size()))) {
						out.add(Motors.describe(m));
					}
					Map<String, Object> res = new LinkedHashMap<>();
					res.put("matches", found.size());
					res.put("motors", out);
					return res;
				}));

		s.tool(new ToolDef("set_motor", "Put a motor in a motor mount",
				"Assign a motor (by designation such as \"M1850W\", \"CTI L1115\", or digest) to a motor mount for a flight "
						+ "configuration. Also sets ejection delay and ignition (use ignitionEvent=burnout/ejection_charge with a delay "
						+ "for air-started upper stages).",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("mount", "Motor mount component (inner tube / body tube) id or name.", true)
						.str("motor", "Designation, \"Manufacturer Designation\" or digest.", true)
						.str("manufacturer", "Manufacturer to disambiguate.", false)
						.str("configuration", DesignTools.CONFIG + " A new configuration is created when the design has none.", false)
						.qty("ejectionDelay", "Motor ejection delay; use \"plugged\" semantics by omitting for motor-less deployment.", false)
						.enumStr("ignitionEvent", "Ignition event.", false, "automatic", "launch", "ejection_charge", "burnout", "never")
						.qty("ignitionDelay", "Delay after the ignition event.", false)
						.qty("overhang", "Motor overhang beyond the mount aft end.", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Rocket rocket = d.doc.getRocket();
					RocketComponent comp = Components.find(rocket, a.str("mount"));
					if (!(comp instanceof MotorMount mount)) {
						throw new ToolException("'" + comp.getName() + "' cannot hold a motor (use an inner tube or body tube).");
					}
					ThrustCurveMotor motor = Motors.find(a.str("motor"), a.str("manufacturer", null));
					if (motor.getDiameter() > mount.getMotorMountDiameter() + 0.0005) {
						throw new ToolException(motor.getDesignation() + " (" + Units.fmt(motor.getDiameter(), Dim.LENGTH)
								+ ") does not fit mount " + comp.getName() + " (" + Units.fmt(mount.getMotorMountDiameter(), Dim.LENGTH) + ").");
					}
					FlightConfiguration fc;
					if (rocket.getIds().isEmpty() || (!a.has("configuration") && !rocket.getSelectedConfiguration().hasMotors()
							&& rocket.getConfigurationCount() == 0)) {
						fc = rocket.createFlightConfiguration(new info.openrocket.core.rocketcomponent.FlightConfigurationId());
						rocket.setSelectedConfiguration(fc.getId());
					} else {
						fc = Components.config(rocket, a.str("configuration", null));
					}
					mount.setMotorMount(true);
					MotorConfiguration mc = new MotorConfiguration(mount, fc.getId());
					mc.setMotor(motor);
					if (a.has("ejectionDelay")) {
						mc.setEjectionDelay(a.qty("ejectionDelay", Dim.TIME));
					} else {
						double[] delays = motor.getStandardDelays();
						mc.setEjectionDelay(delays != null && delays.length > 0 ? delays[delays.length - 1] : Double.NaN);
					}
					if (a.has("ignitionEvent")) {
						mc.setIgnitionEvent(IgnitionEvent.valueOf(a.str("ignitionEvent").toUpperCase()));
					}
					if (a.has("ignitionDelay")) {
						mc.setIgnitionDelay(a.qty("ignitionDelay", Dim.TIME));
					}
					if (a.has("overhang")) {
						mount.setMotorOverhang(a.qty("overhang", Dim.LENGTH));
					}
					mount.setMotorConfig(mc, fc.getId());
					fc.update();
					d.doc.setSaved(false);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("configuration", fc.getId().toString().substring(0, 8) + " \"" + fc.getName() + "\"");
					out.put("mount", comp.getName());
					out.put("motor", Motors.describe(motor));
					return out;
				}));

		s.tool(new ToolDef("rank_motors", "Rank motors by simulating them",
				"Simulate every candidate motor that fits a mount and rank them. objective: target_apogee (closest to "
						+ "targetApogee), max_apogee, or min_impulse_meeting_rules. Each row reports apogee, rail exit speed, "
						+ "thrust-to-weight, max Mach and optimum delay, and whether rail exit and TWR meet the rule set. The design is "
						+ "left unchanged; use set_motor to apply a choice.",
				filterSchema(Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("mount", "Motor mount id or name.", true)
						.str("configuration", DesignTools.CONFIG, false)
						.enumStr("objective", "Ranking objective.", false, "target_apogee", "max_apogee", "min_impulse_meeting_rules")
						.qty("targetApogee", "Target apogee AGL for objective=target_apogee.", false)
						.integer("maxCandidates", "Maximum motors to simulate (default 30, max 80).", false)).build(),
				true, a -> rank(ctx, a)));

		s.tool(new ToolDef("import_motor_file", "Import motor files (.eng/.rse)",
				"Load RASP (.eng), RockSim (.rse) or zipped motor files into this session's motor database, e.g. thrust curves "
						+ "from thrustcurve.org or your static-fire data. Imported motors can then be used with set_motor.",
				Schema.object().str("path", "Path to the motor file.", true).build(), false, a -> {
					List<ThrustCurveMotor> motors = Motors.importFile(Path.of(a.str("path")));
					List<Map<String, Object>> out = new ArrayList<>();
					for (ThrustCurveMotor m : motors) {
						out.add(Motors.describe(m));
					}
					return Map.of("imported", out);
				}));

		s.tool(new ToolDef("create_custom_motor", "Create a custom engine (liquid, hybrid, research)",
				"Define an engine from a thrust curve (e.g. static-fire data) and masses, and add it to the motor database. Writes a "
						+ "RockSim .rse file that stores mass AND center of gravity at every point, so the CG shift of propellant tanks "
						+ "is modeled: for a liquid or hybrid, define the 'motor' as the whole propulsion system (tanks + engine), give "
						+ "its total and propellant mass, and dryCgFromTop / propellantCgFromTop measured from the top of that system. "
						+ "Copy the .rse into OpenRocket's motor folder to use it in the GUI.",
				Schema.object()
						.str("designation", "Motor name, e.g. \"Discovery-N5800\".", true)
						.str("manufacturer", "Manufacturer / team name.", true)
						.enumStr("type", "Engine type (OpenRocket has no liquid type; liquid is stored as hybrid, which has no ejection charge).",
								false, "liquid", "hybrid", "solid", "reload")
						.qty("diameter", "Outer diameter of the motor / propulsion system.", true)
						.qty("length", "Length of the motor / propulsion system.", true)
						.qty("totalMass", "Loaded mass (dry + propellant).", true)
						.qty("propellantMass", "Propellant mass (fuel + oxidizer).", true)
						.prop("thrustCurve", thrustCurveSchema(), false)
						.qty("averageThrust", "With burnTime, builds a flat curve when no thrustCurve is given.", false)
						.qty("burnTime", "Burn time for a flat curve.", false)
						.qty("dryCgFromTop", "CG of the empty system from its top (default: half length).", false)
						.qty("propellantCgFromTop", "CG of the propellant from the top (e.g. tank centroid).", false)
						.array("delays", "Ejection delays in s (omit for plugged).", Schema.type("number", null), false)
						.str("saveTo", "Where to write the .rse file (default: ./motors/<designation>.rse).", false)
						.str("comment", "Free-text description.", false).build(),
				false, a -> {
					double[][] curve = curve(a);
					double[] delays = null;
					if (a.has("delays")) {
						JsonArray arr = a.array("delays");
						delays = new double[arr.size()];
						for (int i = 0; i < arr.size(); i++) {
							delays[i] = arr.get(i).getAsDouble();
						}
					}
					String designation = a.str("designation");
					Path file = Path.of(a.str("saveTo", "motors/" + designation.replaceAll("[^A-Za-z0-9._-]", "_") + ".rse"));
					ThrustCurveMotor m = Motors.createCustom(new Motors.CustomMotor(a.str("manufacturer"), designation,
							a.str("type", "liquid"), a.qty("diameter", Dim.LENGTH), a.qty("length", Dim.LENGTH),
							a.qty("totalMass", Dim.MASS), a.qty("propellantMass", Dim.MASS), curve[0], curve[1],
							a.qtyOrNaN("dryCgFromTop", Dim.LENGTH), a.qtyOrNaN("propellantCgFromTop", Dim.LENGTH), delays,
							a.str("comment", null)), file);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("motor", Motors.describe(m));
					out.put("file", file.toAbsolutePath().normalize().toString());
					out.put("note", "Available to set_motor in this session. The .rse file lets the OpenRocket app and teammates use the same curve.");
					return out;
				}));
	}

	private static com.google.gson.JsonObject thrustCurveSchema() {
		com.google.gson.JsonObject point = new com.google.gson.JsonObject();
		point.addProperty("type", "array");
		point.add("items", Schema.type("number", null));
		point.addProperty("minItems", 2);
		point.addProperty("maxItems", 2);
		com.google.gson.JsonObject s = Schema.type("array", "Thrust curve as [[time_s, thrust_N], ...], starting near t=0.");
		s.add("items", point);
		return s;
	}

	private static double[][] curve(Args a) {
		if (a.has("thrustCurve")) {
			JsonArray arr = a.array("thrustCurve");
			double[] t = new double[arr.size()];
			double[] f = new double[arr.size()];
			for (int i = 0; i < arr.size(); i++) {
				JsonElement p = arr.get(i);
				if (!p.isJsonArray() || p.getAsJsonArray().size() != 2) {
					throw new ToolException("thrustCurve points must be [time_s, thrust_N].");
				}
				t[i] = p.getAsJsonArray().get(0).getAsDouble();
				f[i] = p.getAsJsonArray().get(1).getAsDouble();
			}
			return new double[][] { t, f };
		}
		if (a.has("averageThrust") && a.has("burnTime")) {
			double f = a.qty("averageThrust", Dim.FORCE);
			double tb = a.qty("burnTime", Dim.TIME);
			double ramp = Math.min(0.05, tb * 0.02);
			return new double[][] { { 0, ramp, tb - ramp, tb }, { 0, f * tb / (tb - ramp), f * tb / (tb - ramp), 0 } };
		}
		throw new ToolException("Give thrustCurve, or averageThrust and burnTime.");
	}

	private static Object rank(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Rocket rocket = d.doc.getRocket();
		RocketComponent comp = Components.find(rocket, a.str("mount"));
		if (!(comp instanceof MotorMount mount)) {
			throw new ToolException("'" + comp.getName() + "' is not a motor mount.");
		}
		FlightConfiguration fc = Components.config(rocket, a.str("configuration", null));
		List<ThrustCurveMotor> candidates = Motors.search(filter(a, mount.getMotorMountDiameter()));
		int max = Math.min(80, a.integer("maxCandidates", 30));
		if (candidates.isEmpty()) {
			throw new ToolException("No motors match the filters for a " + Units.fmt(mount.getMotorMountDiameter(), Dim.LENGTH) + " mount.");
		}
		// Spread the sample over the impulse range when there are more candidates than we simulate.
		List<ThrustCurveMotor> sample = new ArrayList<>();
		if (candidates.size() <= max) {
			sample.addAll(candidates);
		} else {
			for (int i = 0; i < max; i++) {
				sample.add(candidates.get((int) Math.round((double) i * (candidates.size() - 1) / (max - 1))));
			}
		}
		MotorConfiguration original = mount.getMotorConfig(fc.getId());
		double railMin = ctx.standards().rule("railDepartureVelocity.min", Dim.VELOCITY);
		String objective = a.str("objective", a.has("targetApogee") ? "target_apogee" : "max_apogee");
		double target = a.qty("targetApogee", Dim.DISTANCE, Double.NaN);
		if (objective.equals("target_apogee") && Double.isNaN(target)) {
			throw new ToolException("targetApogee is required for objective=target_apogee.");
		}
		Simulation base = Sims.prepare(d, null, fc.getId().toString(), Sims.Overrides.none(), ctx.standards(), false);
		String mountId = comp.getID().toString();
		List<Simulation> variants = new ArrayList<>();
		for (ThrustCurveMotor m : sample) {
			variants.add(Variants.of(base, d.doc, r -> {
				MotorMount mm = (MotorMount) Components.find(r, mountId);
				MotorConfiguration mc = new MotorConfiguration(mm, fc.getId());
				mc.setMotor(m);
				mc.setEjectionDelay(original.getEjectionDelay());
				mc.setIgnitionEvent(original.getIgnitionEvent());
				mc.setIgnitionDelay(original.getIgnitionDelay());
				mm.setMotorConfig(mc, fc.getId());
				r.getFlightConfiguration(fc.getId()).update();
			}, null));
		}
		List<Variants.Run> runs = Variants.runAll(variants);
		List<Object[]> rows = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			if (!runs.get(i).ok()) {
				continue;
			}
			ThrustCurveMotor m = sample.get(i);
			FlightData data = runs.get(i).sim().getSimulatedData();
			double liftMass = data.getBranch(0).getByIndex(FlightDataType.TYPE_MASS, 0);
			double twr = m.getAverageThrustEstimate() * original.getMotorCount() / (liftMass * Atmosphere.G0);
			rows.add(new Object[] { m, data.getMaxAltitude(), data.getLaunchRodVelocity(), twr, data.getMaxMachNumber(),
					data.getOptimumDelay() });
		}
		switch (objective) {
			case "target_apogee" -> rows.sort((x, y) -> Double.compare(Math.abs((double) x[1] - target), Math.abs((double) y[1] - target)));
			case "min_impulse_meeting_rules" -> {
				rows.removeIf(r -> !Double.isNaN(railMin) && (double) r[2] < railMin);
				rows.sort((x, y) -> Double.compare(((ThrustCurveMotor) x[0]).getTotalImpulseEstimate(), ((ThrustCurveMotor) y[0]).getTotalImpulseEstimate()));
			}
			default -> rows.sort((x, y) -> Double.compare((double) y[1], (double) x[1]));
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object[] r : rows) {
			ThrustCurveMotor m = (ThrustCurveMotor) r[0];
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("motor", m.getManufacturer().getSimpleName() + " " + m.getDesignation());
			row.put("class", Motors.impulseClass(m.getTotalImpulseEstimate()));
			row.put("totalImpulse", Units.fmt(m.getTotalImpulseEstimate(), Dim.IMPULSE));
			row.put("apogee", Units.fmt((double) r[1], Dim.DISTANCE));
			if (!Double.isNaN(target)) {
				row.put("apogeeError", Units.fmt((double) r[1] - target, Dim.DISTANCE));
			}
			row.put("railExitVelocity", Units.fmt((double) r[2], Dim.VELOCITY)
					+ (!Double.isNaN(railMin) && (double) r[2] < railMin ? " (below rule)" : ""));
			row.put("thrustToWeight", Units.num((double) r[3]));
			row.put("maxMach", Units.num((double) r[4]));
			row.put("optimumDelay", Units.fmt((double) r[5], Dim.TIME));
			row.put("length", Units.fmt(m.getLength(), Dim.LENGTH));
			out.add(row);
		}
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("mount", comp.getName() + " (" + Units.fmt(mount.getMotorMountDiameter(), Dim.LENGTH) + ")");
		res.put("objective", objective);
		res.put("candidatesMatching", candidates.size());
		res.put("simulated", sample.size());
		res.put("ranking", out.subList(0, Math.min(20, out.size())));
		res.put("note", "Motor length is not checked against the mount; confirm it fits. Uses the configuration's existing "
				+ "simulation settings (or the team's launch-site defaults).");
		return res;
	}
}
