// Everything the search can find: the analyses, the pages, the actions in the top bar, parts to add, the parts of the
// open design, and settings. Built when the search opens, so it follows the design and the selection.

import { app, addPart, findNode, newDesign, openDialog, placement, redo, save, select, setSystem, undo, type Page, type TreeNode } from "../state.svelte";
import { CATALOG, type Analysis } from "../analysis/catalog";
import { GUIDES } from "../analysis/guides";
import { analysis } from "../analysis/store.svelte";
import { PART_GROUPS } from "../design/parts";
import { openIssues } from "../issues/issues.svelte";
import { prefs, SCHEMES, setScheme, setTheme } from "../prefs.svelte";
import { assistant, who } from "../assistant/assistant.svelte";
import { keys, MAC } from "../keys";
import { ui } from "./ui.svelte";
import type { Entry } from "./search";

/** The words people use for each analysis that its name does not contain. */
export const ANALYSIS_KEYS: Record<string, string> = {
  check_requirements: "rules compliance DTEG Launch Canada pass fail rail exit thrust to weight stability check requirements",
  design_status: "status todo to do next steps overview what is missing readiness",
  verify_claims: "meeting notes slack notion claims fact check verify numbers",
  advanced_probation: "probation AASI hybrid liquid GLPP Isp static fire",
  aero_analysis: "drag Cd CP centre center of pressure static margin Mach transonic supersonic CNalpha aerodynamics",
  ballast: "nose weight add weight stability CG centre of gravity",
  roll_analysis: "roll spin cant misalignment resonance",
  compare_shapes: "nose cone shape ogive Von Karman Haack conical airfoil fin edge profile",
  recovery_analysis: "opening shock load deployment parachute drogue main shear pins snatch recovery",
  parachute_sizes: "chute size descent rate diameter chart Cd",
  size_parachute: "chute parachute size descent rate diameter CdA",
  deployment_delay_sweep: "late deployment delay backup timing",
  recovery_sections: "sections tethered landing energy kinetic bay fill",
  shear_pins: "shear pins nylon screws 4-40 2-56 separation",
  ejection_charge: "black powder BP charge ejection pressurize ground test",
  opening_shock: "opening load shock snatch force Knacke Cx",
  recovery_bay_fit: "packing fit bay length volume shock cord",
  search_parachutes: "parachute catalog catalogue Fruity Chutes Rocketman buy",
  descent_energy: "landing energy kinetic energy ft-lbf",
  fin_flutter: "flutter divergence fin speed shear modulus thickness",
  fin_fea: "FEA finite element stress Ansys Abaqus CalculiX deflection natural frequency vibration modal structural",
  structural_loads: "airframe loads bending moment axial compression coupler buckling max q structure",
  aero_heating: "heating temperature thermal stagnation",
  compare_materials: "material carbon fiberglass fibreglass G10 aluminum aluminium trade study layup",
  pressure_vessel: "tank COPV burst proof MEOP chamber Barlow",
  add_avionics_bay: "avbay av-bay electronics bay altimeter battery switch sled",
  weather_forecast: "weather forecast wind Open-Meteo launch day temperature",
  rail_buttons: "rail buttons lug rail exit tip-off",
  flight_card: "flight card launch day RSO checklist",
  altimeter_settings: "altimeter deploy altitude Mach lockout backup static port",
  sensor_check: "sensors accelerometer IMU gyro barometer GPS saturate range",
  sensor_data: "flight software HIL test data CSV simulate sensors IMU",
  radio_link: "radio telemetry link budget tracker antenna LoRa range",
  power_budget: "battery power e-match current brownout",
  wind_profile: "winds aloft wind shear wind profile power law",
  monte_carlo: "dispersion landing zone ellipse uncertainty statistics random scatter",
  rank_motors: "motor selection choose motor best motor thrustcurve impulse",
  sweep: "parameter study trade vary what if",
  optimize_fins: "fin design fin optimiser optimizer planform fin size",
  optimize: "optimiser goal seek target apogee minimize minimise mass",
  add_from_link: "shop URL product buy import part link",
  compare_designs: "diff compare changes baseline version git",
  progress_log: "progress weekly update status report milestone PDR CDR",
  history: "undo list edit history changes",
  import_aero_table: "RASAero CFD aero table drag import",
  compare_flight: "flight log altimeter data actual vs predicted calibrate post-flight",
  generate_report: "report design review PDR CDR document",
  export_flight_data: "CSV export flight data MATLAB Excel spreadsheet",
  export_geometry: "STL DXF CAD CFD OpenFOAM export shape waterjet laser cut",
  mass_budget: "mass budget weight weigh-in mass sheet",
};

/** An analysis's searchable text: its group, summary and guide. */
export function analysisText(a: Analysis, group: string): string {
  const g = GUIDES[a.tool];
  return [group, a.blurb ?? "", ...(g ? [...g.needs, ...g.gives, g.how ?? "", g.not ?? ""] : [])].join(" ");
}

const PAGES: { id: Page; title: string; sub: string; keys: string }[] = [
  { id: "home", title: "Start", sub: "Recent files and examples", keys: "home recent examples open file" },
  { id: "design", title: "Design", sub: "The rocket's parts, the 3-D view and the rule check", keys: "parts components edit 3D view tree" },
  { id: "motors", title: "Motors", sub: "Motors, configurations, deployment and staging", keys: "motor configuration delay ignition staging separation deployment" },
  { id: "flights", title: "Flights", sub: "Simulations, launch conditions and plots", keys: "simulation simulate flight apogee plot launch conditions" },
  { id: "targets", title: "Targets", sub: "The team's targets and the competition rules", keys: "goals requirements apogee target" },
  { id: "standards", title: "Standards", sub: "Rule set, launch site, safety factors, ratings and materials", keys: "rule set launch site safety factor pin ratings materials team standards" },
  { id: "analysis", title: "Analysis", sub: "Rule check, recovery, structures, studies", keys: "analyses tools" },
  { id: "flightdata", title: "Flight data", sub: "Altimeter logs and past rockets", keys: "altimeter logs past rockets library" },
];

function go(page: Page) {
  app.page = page;
}

function flatten(n: TreeNode | undefined, out: TreeNode[] = []): TreeNode[] {
  if (!n) return out;
  if (n.id !== "rocket") out.push(n);
  for (const c of n.children) flatten(c, out);
  return out;
}

export function entries(): Entry[] {
  const open = !!app.design;
  const noDesign = open ? undefined : "Open or start a design first";
  const out: Entry[] = [];

  for (const g of CATALOG) {
    for (const a of g.items) {
      out.push({
        kind: "analysis",
        title: a.label,
        sub: a.blurb,
        keys: ANALYSIS_KEYS[a.tool] ?? "",
        text: analysisText(a, g.title),
        off: noDesign,
        run: () => {
          analysis.selected = a.tool;
          go("analysis");
        },
      });
    }
  }

  for (const p of PAGES) {
    out.push({ kind: "page", title: p.title, sub: p.sub, keys: p.keys, off: p.id === "home" ? undefined : noDesign, run: () => go(p.id) });
  }

  out.push(
    { kind: "action", title: "New design", sub: "Start an empty rocket", keys: "create blank", run: newDesign },
    { kind: "action", title: "Open design", sub: "An .ork or .rkt file", keys: "load file ork rkt rocksim", shortcut: keys("O"), run: openDialog },
    { kind: "action", title: "Save", sub: "Save the design", keys: "write file", shortcut: keys("S"), off: noDesign, run: () => save(false) },
    { kind: "action", title: "Save as", sub: "Save under a new name", keys: "copy file", shortcut: keys("S", true), off: noDesign, run: () => save(true) },
    { kind: "action", title: "Undo", sub: "Undo the last edit", shortcut: keys("Z"), off: open && app.design?.canUndo ? undefined : "Nothing to undo", run: undo },
    { kind: "action", title: "Redo", sub: "Redo the edit undone", shortcut: MAC ? keys("Z", true) : keys("Y"), off: open && app.design?.canRedo ? undefined : "Nothing to redo", run: redo },
    { kind: "action", title: "Problems", sub: "Errors and warnings, and reporting a bug", keys: "errors warnings issues bug report", run: openIssues },
    { kind: "action", title: "Choose a motor", sub: "Motors page", keys: "set motor pick motor", off: noDesign, run: () => go("motors") },
  );
  if (assistant.settings?.enabled) {
    out.push({ kind: "action", title: `Ask ${who()}`, sub: "The AI assistant, about this design", keys: "AI assistant chat claude codex", run: () => (assistant.open = true) });
  }

  // Parts to add, where they would go from the current selection.
  for (const g of PART_GROUPS) {
    for (const [type, label] of g.parts) {
      const at = open ? placement(type) : null;
      const into = at ? (at.parent === "rocket" ? app.design!.tree.name : findNode(app.design?.tree, at.parent)?.name) : null;
      out.push({
        kind: "add",
        title: `Add ${label.toLowerCase()}`,
        sub: into ? `Into ${into}` : g.title,
        keys: `${label} ${type} ${g.title} new part component`,
        off: noDesign ?? (into ? undefined : "Can't go here: select a part it fits in"),
        run: () => {
          go("design");
          addPart(type);
        },
      });
    }
  }
  out.push({
    kind: "add",
    title: "Add avionics bay",
    sub: "Coupler, bulkheads, altimeters, batteries, charges and tracker",
    keys: "avbay electronics bay",
    off: noDesign,
    run: () => {
      analysis.selected = "add_avionics_bay";
      go("analysis");
    },
  });

  // The open design's own parts.
  for (const n of flatten(app.design?.tree)) {
    out.push({
      kind: "part",
      title: n.name,
      sub: n.label,
      keys: n.type,
      run: () => {
        go("design");
        select(n.id);
      },
    });
  }

  const settings = (section: string) => () => (ui.settings = section);
  out.push(
    { kind: "setting", title: "Metric units", sub: "m, kg, N, km/h", keys: "units SI", run: () => setSystem("metric") },
    { kind: "setting", title: "Imperial units", sub: "in, lb, lbf, ft/s", keys: "units US", run: () => setSystem("imperial") },
    { kind: "setting", title: "Units for each quantity", sub: "Settings", keys: "unit preferences", run: settings("Units") },
    { kind: "setting", title: "Light theme", keys: "appearance mode bright", run: () => setTheme("light") },
    { kind: "setting", title: "Dark theme", keys: "appearance mode night", run: () => setTheme("dark") },
    { kind: "setting", title: "Theme follows the system", keys: "appearance mode auto", run: () => setTheme("system") },
    { kind: "setting", title: "Automatic rule check", sub: "Settings", keys: "checks auto", run: settings("Checks") },
    { kind: "setting", title: "AI assistant", sub: "Settings: Claude, ChatGPT or an API key", keys: "claude codex chatgpt api key connect", run: settings("AI assistant") },
  );
  for (const sc of SCHEMES) {
    out.push({
      kind: "setting",
      title: `${sc.name} colour scheme`,
      sub: sc.vision ?? (prefs.scheme === sc.id ? "In use" : "Appearance"),
      keys: "color colour scheme theme appearance",
      run: () => setScheme(sc.id),
    });
  }
  return out;
}
