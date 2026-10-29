// The engine's analyses as the Analysis page lists them. `main`: the inputs shown up front (the rest fold away);
// `defaults`: starting values, given the design's file-safe name.

export interface Analysis {
  tool: string;
  label: string;
  blurb?: string;
  main: string[];
  defaults?: (name: string) => Record<string, string>;
}

export interface Group {
  title: string;
  items: Analysis[];
}

export const CATALOG: Group[] = [
  {
    title: "Checks",
    items: [
      { tool: "check_requirements", label: "Rule check", blurb: "Simulates the flight and checks it against the competition rules and your team standards.", main: ["simulation", "includeWindCase"] },
      { tool: "design_status", label: "Readiness", blurb: "What is done, what is missing, and what to do next.", main: ["configuration"] },
    ],
  },
  {
    title: "Stability and drag",
    items: [
      { tool: "aero_analysis", label: "Stability and drag vs Mach", blurb: "Drag coefficient, CP and static margin across Mach numbers, with the drag breakdown.", main: ["stage", "machs", "maxMach"] },
      { tool: "ballast", label: "Nose ballast", blurb: "Nose ballast needed to reach a stability target, and what it costs in apogee.", main: ["targetStability", "component", "position"] },
      { tool: "roll_analysis", label: "Roll from fin cant", blurb: "Roll rate from fin cant or misalignment.", main: ["finSet", "cantAngles", "maxRollRate"] },
      { tool: "compare_shapes", label: "Nose and fin shapes", blurb: "Flies other nose cone shapes and lengths, and fin shapes, against the current design.", main: ["noseCone", "noseLengths", "fins"] },
    ],
  },
  {
    title: "Recovery",
    items: [
      { tool: "recovery_analysis", label: "Recovery loads", blurb: "Each deployment from the simulated flight: airspeed, opening load, descent rate, shear pins.", main: ["simulation", "pinType", "pinStrength"] },
      { tool: "parachute_sizes", label: "Parachute size chart", blurb: "Descent rate against canopy diameter for a few drag coefficients.", main: ["device", "cds", "targetDescentRate", "apply"] },
      { tool: "size_parachute", label: "Size a parachute", blurb: "Canopy diameter for a target descent rate.", main: ["targetDescentRate", "device", "cd", "count"] },
      { tool: "deployment_delay_sweep", label: "Late deployment", blurb: "How the opening load grows if a device deploys late.", main: ["device", "delays", "pinType", "pinCount"] },
      { tool: "recovery_sections", label: "Recovery sections", blurb: "The sections that come down separately, with their masses.", main: ["joints"] },
      { tool: "shear_pins", label: "Shear pins", blurb: "Number of shear pins to hold a load, with a safety factor.", main: ["holdForce", "pinType", "pinStrength", "safetyFactor"] },
      { tool: "ejection_charge", label: "Ejection charge", blurb: "Black powder for a bay, from its volume and the pins to break.", main: ["bayTube", "bayDiameter", "bayLength", "pinType", "pinCount", "pressure"] },
      { tool: "opening_shock", label: "Opening shock", blurb: "Opening load of a canopy at a given speed and mass.", main: ["mass", "velocity", "cd", "diameter"] },
    ],
  },
  {
    title: "Structures",
    items: [
      { tool: "fin_flutter", label: "Fin flutter", blurb: "Fin flutter speed against airspeed along the flight.", main: ["finSet", "shearModulus", "thickness"] },
      { tool: "structural_loads", label: "Airframe loads", blurb: "Axial and bending loads at each airframe joint, at max q with a gust.", main: ["gustSpeed", "safetyFactor", "allowableStress"] },
      { tool: "aero_heating", label: "Aerodynamic heating", blurb: "Skin temperature screen for the nose and fins.", main: ["noseTipRadius"] },
      { tool: "compare_materials", label: "Material trade", blurb: "Flies the fins or airframe in other materials and thicknesses.", main: ["parts", "materials", "thicknesses"] },
      { tool: "pressure_vessel", label: "Pressure vessel", blurb: "Burst and proof margins of a tank or motor case.", main: ["meop", "burstPressure", "outerDiameter", "wallThickness", "ultimateStrength"] },
    ],
  },
  {
    title: "Launch and avionics",
    items: [
      { tool: "rail_buttons", label: "Rail buttons", blurb: "Rail button positions, rail exit speed and tip-off.", main: ["forward", "aft", "crosswind", "apply"] },
      { tool: "flight_card", label: "Flight card", blurb: "Writes the launch-day flight card.", main: ["path"], defaults: (n) => ({ path: `${n}-flight-card.md` }) },
      { tool: "altimeter_settings", label: "Altimeter settings", blurb: "Settings for the primary and backup altimeters: deployment, Mach lockout, port size.", main: ["bay", "bayVolume"] },
      { tool: "sensor_check", label: "Sensor ranges", blurb: "Sensor ranges against what the flight reaches.", main: ["simulation"] },
      { tool: "radio_link", label: "Radio link", blurb: "Tracker radio link margin in flight and after landing.", main: ["frequencyMhz", "txPowerDbm", "rxSensitivityDbm", "groundStationHeight"] },
    ],
  },
  {
    title: "Studies",
    items: [
      { tool: "monte_carlo", label: "Monte Carlo dispersion", blurb: "Many flights with varied wind, launch angle and more: landing spread and worst cases.", main: ["runs", "windSpeed", "windSpeedSd", "windDirection"] },
      { tool: "rank_motors", label: "Rank motors", blurb: "Flies each motor that fits the mount and ranks them.", main: ["mount", "objective", "targetApogee", "minClass", "maxClass", "certLevel"] },
      { tool: "sweep", label: "Parameter sweep", blurb: "Flies the design with one property set to each value in a list.", main: ["component", "parameter", "values"] },
    ],
  },
  {
    title: "Files",
    items: [
      { tool: "generate_report", label: "Design review report", blurb: "Writes a design review report with plots.", main: ["outputDir", "title"], defaults: (n) => ({ outputDir: `reports/${n}` }) },
      { tool: "export_flight_data", label: "Flight data (CSV)", blurb: "Writes the flight data of a simulation as CSV.", main: ["path", "variables"], defaults: (n) => ({ path: `${n}-flight.csv` }) },
      { tool: "export_geometry", label: "Geometry for CAD and CFD", blurb: "Writes the outer shape and flight conditions for CAD and CFD.", main: ["outDir", "units"], defaults: (n) => ({ outDir: `geometry/${n}` }) },
      { tool: "mass_budget", label: "Mass budget sheet", blurb: "Writes a mass budget sheet to fill in with weighed masses.", main: ["targetLaunchMass", "contingency"] },
    ],
  },
];

/** Launch-condition overrides most flight tools take; they start folded. */
export const OVERRIDES = ["windSpeed", "windDirection", "windTurbulence", "railLength", "launchAngle", "launchDirection",
  "launchIntoWind", "launchSiteAltitude", "latitude", "longitude", "temperature"];

/** Inputs that name a part, and which parts fit (by component type; "mount": any motor mount). */
export const PART_INPUTS: Record<string, string[] | "any" | "mount"> = {
  finSet: ["TrapezoidFinSet", "EllipticalFinSet", "FreeformFinSet", "TubeFinSet"],
  device: ["Parachute", "Streamer"],
  mount: "mount",
  noseCone: ["NoseCone"],
  bayTube: ["BodyTube", "TubeCoupler"],
  bayComponent: ["BodyTube", "TubeCoupler", "NoseCone"],
  component: "any",
};
