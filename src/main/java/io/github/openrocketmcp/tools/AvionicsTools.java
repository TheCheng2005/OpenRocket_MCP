package io.github.openrocketmcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TubeCoupler;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.AltimeterSettings;
import io.github.openrocketmcp.or.Avionics;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Geometry;
import io.github.openrocketmcp.or.SensorSim;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Electronics: the avionics bay, simulated sensor data, sensor ranges and altimeter settings. */
public final class AvionicsTools {
	private AvionicsTools() {
	}

	static final double IN = Avionics.IN;

	public static void register(McpServer s, Context ctx) {
		JsonObject item = Schema.object().str("name", "Part name, e.g. \"Missile Works RRC3\".", true)
				.qty("mass", "Mass, e.g. \"17 g\".", false).qty("length", "Length along the rocket, e.g. \"3.9 in\".", false).build();
		s.tool(new ToolDef("add_avionics_bay", "Add a dual-deploy avionics bay",
				"Build the electronics bay between two airframe tubes, laid out the way Launch Canada asks: a coupler (with an "
						+ "optional switch band) closed by two bulkheads; independent altimeter circuits, each with its own battery and "
						+ "physical switch; primary and backup ejection charges on each bulkhead (drogue aft, main forward) with "
						+ "U-bolts; the GPS tracker on its own battery in an RF-transparent nose (or in the bay); and, by default, "
						+ "the parachutes and shock cords packed against the bay. All parts are typed OpenRocket components "
						+ "(altimeter, battery, tracker, deployment charge), so mass, CG, stability and draw_rocket include them. "
						+ "Returns the layout, bay mass and static port sizes. Pass your real parts (name, mass, length) for "
						+ "accurate masses.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false)
						.str("upperTube", "Airframe tube forward of the bay (holds the main). Default: when the stage has exactly two "
								+ "body tubes, the first.", false)
						.str("lowerTube", "Airframe tube aft of the bay (holds the drogue). Default: the tube after upperTube.", false)
						.qty("couplerLength", "Coupler length (default 3 calibers, rounded to the inch).", false)
						.qty("switchBand", "Switch band length between the tubes (default 1 in; 0 for none).", false)
						.array("altimeters", "Altimeters, one circuit each (default a primary and a backup; use two different "
								+ "models).", item, false)
						.obj("battery", "Battery used for each altimeter (default 9 V, 46 g, 2 in).", false)
						.obj("switch", "Physical switch for each altimeter (default screw switch, 10 g).", false)
						.enumStr("tracker", "Where the GPS tracker goes (default nose).", false, "nose", "bay", "none")
						.obj("trackerPart", "The tracker (default GPS tracker, 30 g, 2.5 in).", false)
						.obj("trackerBattery", "The tracker's battery (default 1S LiPo, 20 g, 2 in).", false)
						.qty("chargeMass", "Each charge well with its black powder (default 12 g).", false)
						.qty("sledMass", "Sled, threaded rods and nuts (default 120 g).", false)
						.qty("bulkheadThickness", "Bulkhead thickness (default 1/4 in).", false)
						.str("bulkheadMaterial", "Bulkhead material (default Fiberglass; e.g. Birch for plywood).", false)
						.bool("packRecovery", "Move the parachutes and shock cords against the bay (default true).", false).build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					var rocket = d.doc.getRocket();
					BodyTube upper, lower;
					if (a.has("upperTube")) {
						upper = Components.find(rocket, a.str("upperTube"), BodyTube.class, "body tube");
					} else {
						List<BodyTube> tubes = new ArrayList<>();
						for (RocketComponent c : rocket.getChild(0).getChildren()) {
							if (c instanceof BodyTube b) {
								tubes.add(b);
							}
						}
						if (tubes.size() != 2) {
							throw new ToolException("Say which tubes the bay goes between (upperTube, lowerTube): the stage has "
									+ tubes.size() + " body tubes.");
						}
						upper = tubes.get(0);
					}
					if (a.has("lowerTube")) {
						lower = Components.find(rocket, a.str("lowerTube"), BodyTube.class, "body tube");
					} else {
						int i = upper.getParent().getChildPosition(upper);
						RocketComponent next = i + 1 < upper.getParent().getChildCount() ? upper.getParent().getChild(i + 1) : null;
						if (!(next instanceof BodyTube b)) {
							throw new ToolException("There is no body tube right after " + upper.getName() + "; pass lowerTube.");
						}
						lower = b;
					}
					double cal = upper.getOuterRadius() * 2;
					double lc = a.qty("couplerLength", Dim.LENGTH, Math.round(3 * cal / IN) * IN);
					List<Avionics.Item> alts = a.has("altimeters") ? items(a.objList("altimeters"), 0.025, 3.5 * IN)
							: Avionics.defaultAltimeters();
					if (alts.isEmpty()) {
						throw new ToolException("Give at least one altimeter.");
					}
					Avionics.Spec spec = new Avionics.Spec(lc, a.qty("switchBand", Dim.LENGTH, 1 * IN), alts,
							item(a.has("battery") ? a.obj("battery") : null, "9 V battery", 0.046, 2 * IN),
							item(a.has("switch") ? a.obj("switch") : null, "Screw switch", 0.010, 1 * IN), a.str("tracker", "nose"),
							item(a.has("trackerPart") ? a.obj("trackerPart") : null, "GPS tracker", 0.030, 2.5 * IN),
							item(a.has("trackerBattery") ? a.obj("trackerBattery") : null, "Tracker battery (1S LiPo)", 0.020, 2 * IN),
							a.qty("chargeMass", Dim.MASS, 0.012), a.qty("sledMass", Dim.MASS, 0.120),
							a.qty("bulkheadThickness", Dim.LENGTH, 0.25 * IN), a.str("bulkheadMaterial", "Fiberglass"),
							a.bool("packRecovery", true));
					return Avionics.add(d, upper, lower, spec);
				}));
		registerSensors(s, ctx);
	}

	/** The sensors to model: the call's list, else electronics.sensors in the team standards. */
	static List<SensorSim.Spec> sensors(Context ctx, Args a) {
		JsonArray arr = a.has("sensors") ? a.array("sensors") : null;
		if (arr == null) {
			JsonObject el = ctx.standards().data().has("electronics") ? ctx.standards().data().getAsJsonObject("electronics") : null;
			arr = el != null && el.has("sensors") ? el.getAsJsonArray("sensors") : null;
		}
		List<SensorSim.Spec> specs = SensorSim.specs(arr);
		if (specs.isEmpty()) {
			throw new ToolException("No sensors: pass sensors, or set electronics.sensors in the team standards.");
		}
		return specs;
	}

	static JsonObject sensorSchema() {
		return Schema.object().str("id", "Short name for the CSV columns, e.g. \"acc\" (letters, digits, _).", false)
				.str("name", "Part, e.g. \"ADXL375 high-g accelerometer\".", false)
				.enumStr("type", "Kind of sensor.", true, "accelerometer", "gyroscope", "barometer", "gps")
				.num("range", "Full scale, ± in g (accelerometer) or deg/s (gyroscope).", false)
				.num("noise", "1-sigma noise: g, deg/s, Pa, or m horizontal for GPS.", false)
				.num("resolution", "One count (LSB) in the same unit.", false)
				.num("min", "Barometer: lowest pressure it reads, Pa (e.g. 30000 for a BMP390).", false)
				.num("max", "Barometer: highest pressure it reads, Pa.", false).build();
	}

	static void registerSensors(McpServer s, Context ctx) {
		s.tool(new ToolDef("sensor_data", "Simulated flight-computer sensor data",
				"Write the readings a flight computer would log on the simulated flight, as CSV at a fixed rate, to test "
						+ "flight software (launch, burnout, apogee and main-altitude detection) before it flies. Accelerometers read "
						+ "specific force in the rocket frame (x along the axis toward the nose; +1 g on the pad, thrust minus drag "
						+ "in flight, the canopy's pull under parachute), gyroscopes the body rates, the barometer ambient pressure "
						+ "and temperature, and GPS fixes at its own rate (none above 515 m/s or 18 km). Each sensor adds its noise, "
						+ "rounds to its resolution and clips at its range, as the real part would. The sensors are the team's "
						+ "(electronics.sensors in the standards) or the list given. Includes pad time before ignition, rest after "
						+ "landing, the true values in truth_ columns, and the true event times in <name>-events.csv for scoring.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("path", "Output CSV (default <design>-sensors.csv).", false)
						.num("rate", "Samples per second (default 100, up to 1000).", false)
						.num("gpsRate", "GPS fixes per second (default 10).", false)
						.qty("padTime", "Time logged on the pad before ignition (default 5 s).", false)
						.qty("restTime", "Time logged after landing (default 3 s).", false)
						.str("branch", "Flight branch (stage) to log, e.g. \"Booster\" (default the sustainer).", false)
						.array("sensors", "Sensors to model instead of the team's.", sensorSchema(), false)
						.integer("seed", "Noise seed (default 1; the same seed gives the same file).", false)
						.bool("truth", "Add the true values (truth_ columns; default true).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation sim = SimTools.runSelected(ctx, a);
					double rate = Math.max(1, Math.min(1000, a.num("rate", 100)));
					SensorSim.Trace tr = SensorSim.trace(sim, a.str("branch", null), rate, a.qty("padTime", Dim.TIME, 5),
							a.qty("restTime", Dim.TIME, 3));
					List<SensorSim.Spec> specs = sensors(ctx, a);
					Path csv = ctx.path(a.str("path", Geometry.safe(d.name()) + "-sensors.csv"));
					int[] size = SensorSim.write(tr, specs, csv, Math.max(0.1, Math.min(rate, a.num("gpsRate", 10))),
							a.integer("seed", 1), a.bool("truth", true));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("file", csv.toString());
					out.put("events", SensorSim.eventsPath(csv).toString());
					out.put("samples", size[0] + " rows x " + size[1] + " columns at " + Units.num(rate) + " Hz ("
							+ Units.num(tr.t()[tr.size() - 1]) + " s: " + Units.num(tr.padTime()) + " s on the pad first)");
					List<String> sensors = new ArrayList<>();
					for (SensorSim.Spec sp : specs) {
						sensors.add(sp.id() + ": " + sp.name());
					}
					out.put("sensors", sensors);
					List<String> ev = new ArrayList<>();
					for (int i = 0; i < tr.eventNames().size(); i++) {
						ev.add("t=" + Units.num(tr.eventTimes().get(i)[0]) + " s " + tr.eventNames().get(i));
					}
					out.put("trueEvents", ev);
					out.put("frame", "x along the rocket toward the nose, y in the pitch plane; accelerations in g (gravity is not "
							+ "sensed: 0 g in free fall, +1 g at rest; drag and the canopy's pull read negative), rates in deg/s, pressure in Pa, "
							+ "GPS in degrees and m MSL");
					out.put("notInTheModel", "vibration, deployment shocks, sensor bias and temperature drift, pressure lag in the "
							+ "bay and port errors near Mach 1 (sensor_check gives that window); add them to stress your filters");
					return out;
				}));

		s.tool(new ToolDef("sensor_check", "Sensor ranges against the flight",
				"Check every sensor of the flight computer against the simulated flight: peak axial and lateral acceleration "
						+ "against each accelerometer's range (saturating during boost is common with ±16 g parts), body rates "
						+ "against the gyroscope, the lowest pressure at apogee against the barometer's range, GPS against the "
						+ "515 m/s / 18 km limits, and when the barometer cannot be trusted near Mach 1. Sensors are the team's "
						+ "(electronics.sensors) or the list given. Give a stronger wind or hotter motor configuration to check "
						+ "the worst case.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.array("sensors", "Sensors to check instead of the team's.", sensorSchema(), false)
						.str("branch", "Flight branch (stage), default the sustainer.", false).build(),
				true, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					SensorSim.Trace tr = SensorSim.trace(sim, a.str("branch", null), 200, 0, 0);
					double mach = ctx.standards().q("electronics.altimeter.baroUnreliableAboveMach", Dim.DIMENSIONLESS, 0.7);
					List<Map<String, Object>> rows = SensorSim.check(tr, sensors(ctx, a), mach);
					Map<String, Object> out = new LinkedHashMap<>();
					int fail = 0, warn = 0, info = 0;
					for (Map<String, Object> r : rows) {
						fail += "FAIL".equals(r.get("status")) ? 1 : 0;
						warn += "WARN".equals(r.get("status")) ? 1 : 0;
						info += "INFO".equals(r.get("status")) ? 1 : 0;
					}
					out.put("summary", fail + " fail, " + warn + " warn, " + (rows.size() - fail - warn - info) + " ok"
							+ (info > 0 ? ", " + info + " not checked (no range given)" : ""));
					out.put("checks", rows);
					out.put("note", "Peaks are this simulation's (sampled at 200 Hz); real boards also see vibration and "
							+ "deployment shocks well above these, so keep headroom on the accelerometers.");
					return out;
				}));

		s.tool(new ToolDef("altimeter_settings", "Altimeter settings sheet",
				"Altimeter settings from the simulation: for every recovery device, the primary altimeter's setting and the "
						+ "backup's (drogue a little after apogee, main a little lower: electronics.altimeter in the standards), "
						+ "the airspeed the backup drogue fires at, the Mach lockout so the barometer cannot fire early on the "
						+ "transonic pressure jump, and the static port size for the bay. Optionally writes a one-page card with "
						+ "the settings and a pre-flight checklist.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("bay", "The avionics bay coupler (default: the coupler named like \"Av-bay\").", false)
						.qty("bayVolume", "Free volume of the bay instead.", false)
						.str("path", "Write the settings card here (Markdown).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation sim = SimTools.runSelected(ctx, a);
					var std = ctx.standards();
					AltimeterSettings.Options o = new AltimeterSettings.Options(
							std.q("electronics.altimeter.backupDrogueDelay", Dim.TIME, 1),
							std.q("electronics.altimeter.backupMainOffset", Dim.DISTANCE, 30.48),
							std.q("electronics.altimeter.baroUnreliableAboveMach", Dim.DIMENSIONLESS, 0.7),
							std.q("electronics.altimeter.machLockoutMargin", Dim.TIME, 1));
					double volume = a.has("bayVolume") ? a.positive("bayVolume", Dim.VOLUME) : 0;
					String bayName = null;
					if (volume == 0) {
						RocketComponent bay = a.has("bay") ? Components.find(d.doc.getRocket(), a.str("bay")) : null;
						if (bay == null) {
							for (RocketComponent c : d.doc.getRocket()) {
								String n = c.getName().toLowerCase(Locale.ROOT);
								if (c instanceof TubeCoupler && (n.contains("av-bay") || n.contains("avbay") || n.contains("avionics")
										|| n.contains("ebay") || n.contains("e-bay"))) {
									bay = c;
									break;
								}
							}
						}
						if (bay != null) {
							volume = AltimeterSettings.bayVolume(bay);
							bayName = bay.getName();
						}
					}
					return AltimeterSettings.sheet(sim, o, volume, bayName, a.has("path") ? ctx.path(a.str("path")) : null);
				}));
	}

	static List<Avionics.Item> items(List<Args> list, double mass, double length) {
		List<Avionics.Item> out = new ArrayList<>();
		for (Args x : list) {
			out.add(item(x, x.str("name"), mass, length));
		}
		return out;
	}

	static Avionics.Item item(Args x, String name, double mass, double length) {
		if (x == null) {
			return new Avionics.Item(name, mass, length);
		}
		return new Avionics.Item(x.str("name", name), x.qty("mass", Dim.MASS, mass), x.qty("length", Dim.LENGTH, length));
	}
}
