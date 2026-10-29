// What the app shows, and everything it does to a design. Each action is one engine tool call followed by a refresh,
// so the screen always shows the engine's own state (and undo covers every edit).

import { call, ToolError } from "./api";
import { units, setSystem as setUnitSystem } from "./units.svelte";

export interface TreeNode {
  id: string;
  name: string;
  type: string;
  label: string;
  motorMount?: boolean;
  overridden?: boolean;
  accepts: string[];
  children: TreeNode[];
}

export interface Config {
  id: string;
  name: string;
  hasMotors: boolean;
}

export interface Stability {
  length?: number;
  diameter?: number;
  cg?: number;
  cgBurnout?: number;
  cp?: number | null;
  margin?: number | null;
  marginBurnout?: number | null;
  mass?: number;
  massBurnout?: number;
  floor?: number;
  warnings?: string[];
  error?: string;
}

export interface Design {
  designId: string;
  name: string;
  path: string | null;
  saved: boolean;
  canUndo: number;
  canRedo: number;
  tree: TreeNode;
  configurations: Config[];
  selected: string;
  stability: Stability;
  loadWarnings?: string[];
}

export interface FieldDef {
  key: string;
  label: string;
  kind: "quantity" | "number" | "count" | "bool" | "enum" | "material" | "text" | "longtext";
  dim?: string;
  value: any;
  auto?: string;
  isAuto?: boolean;
  options?: { value: string; label: string }[];
  materialType?: string;
  help?: string;
  enabled?: boolean;
}

export interface Editor {
  id: string;
  name: string;
  type: string;
  label: string;
  sections: { title: string; fields: FieldDef[] }[];
  mass?: number;
  componentMass?: number;
  warning?: string;
}

export interface Part {
  i: number;
  name: string;
  kind: string;
  color: string;
  internal: boolean;
  piece: number;
  stage: string;
  stageNo: number;
  x0: number;
  x1: number;
  depth: number;
  cutOpen: boolean;
  plateDirs?: number[][];
  explode: number[];
  id?: string;
  type: string;
  mesh: string;
}

export interface Model {
  parts: Part[];
  finOut?: number;
  stability: Stability;
}

export interface QuickFlight {
  noMotors?: boolean;
  apogee?: number;
  maxVelocity?: number;
  maxMach?: number;
  maxAcceleration?: number;
  timeToApogee?: number;
  flightTime?: number;
  groundHitVelocity?: number;
  railExitVelocity?: number;
  optimumDelay?: number;
  minStability?: number;
  maxStability?: number;
  problems?: string[];
  error?: string;
}

export interface Session {
  version: string;
  engineVersion: string;
  examples: string[];
  designs: { designId: string; name: string; path: string }[];
  workspace: string;
  system: string;
  dims: Record<string, any>;
  materials: Record<string, { name: string; density: number }[]>;
  creatable: string[];
}

export type Page = "home" | "design" | "motors" | "flights" | "analysis";

export const app = $state({
  session: null as Session | null,
  page: "home" as Page,
  design: null as Design | null,
  selected: "rocket",
  editor: null as Editor | null,
  model: null as Model | null,
  flight: null as QuickFlight | null,
  flying: false,
  busy: 0,
  toasts: [] as { id: number; text: string; kind: "error" | "info" }[],
  ask: null as null | { title: string; label: string; value: string; resolve: (v: string | null) => void },
  revision: 0, // bumped after every change, so pages reload what they show
  checks: null as Checks | null,
  checking: false,
});

export interface Check {
  status: string;
  item: string;
  requirement?: string;
  value?: string;
  ref?: string;
}

export interface Checks {
  ruleSet?: string;
  summary?: string;
  checks?: Check[];
  error?: string;
  designId: string;
}

const AUTO_CHECK = "rocketmaxxing.autoCheck";

/** Whether the rule check runs after every edit (Settings). */
export function autoCheck(): boolean {
  try {
    return localStorage.getItem(AUTO_CHECK) !== "off";
  } catch {
    return true;
  }
}

export function setAutoCheck(on: boolean) {
  try {
    localStorage.setItem(AUTO_CHECK, on ? "on" : "off");
  } catch {
    /* not kept */
  }
  if (on) checkRules();
}

let checkSeq = 0;
let checkTimer: ReturnType<typeof setTimeout> | undefined;

/** Runs the rule check on the active configuration, a moment after the last edit (edits in a row run it once). */
export function checkRules(delay = 0) {
  const d = app.design;
  if (!d) return;
  clearTimeout(checkTimer);
  const seq = ++checkSeq;
  checkTimer = setTimeout(() => {
    if (seq !== checkSeq) return;
    app.checking = true;
    call<Checks>("check_requirements", { designId: d.designId })
      .then((c) => seq === checkSeq && (app.checks = { ...c, designId: d.designId }))
      .catch((e) => seq === checkSeq && (app.checks = { error: e.message, designId: d.designId }))
      .finally(() => seq === checkSeq && (app.checking = false));
  }, delay);
}

let toastId = 0;

export function toast(text: string, kind: "error" | "info" = "info") {
  const id = ++toastId;
  app.toasts.push({ id, text, kind });
  setTimeout(() => (app.toasts = app.toasts.filter((t) => t.id !== id)), kind === "error" ? 8000 : 3500);
}

/** Runs an engine action, showing its error (if any) instead of throwing. */
export async function run<T>(f: () => Promise<T>): Promise<T | undefined> {
  app.busy++;
  try {
    return await f();
  } catch (e) {
    toast(e instanceof ToolError || e instanceof Error ? e.message : String(e), "error");
    return undefined;
  } finally {
    app.busy--;
  }
}

/** A small question in the app's own dialog (the browser's prompt() is not available in app windows everywhere). */
export function ask(title: string, label: string, value = ""): Promise<string | null> {
  return new Promise((resolve) => (app.ask = { title, label, value, resolve }));
}

export async function init() {
  const s = await call<Session>("app_session");
  app.session = s;
  units.dims = s.dims;
  await call("app_set_units", { system: units.system }).catch(() => {});
  if (s.designs.length > 0) {
    await load(s.designs[s.designs.length - 1].designId);
  }
}

const RECENT = "rocketmaxxing.recent";

export function recent(): string[] {
  try {
    return JSON.parse(localStorage.getItem(RECENT) ?? "[]");
  } catch {
    return [];
  }
}

function remember(path: string | null) {
  if (!path) return;
  try {
    const list = [path, ...recent().filter((p) => p !== path)].slice(0, 8);
    localStorage.setItem(RECENT, JSON.stringify(list));
  } catch {
    /* not kept */
  }
}

async function load(designId: string) {
  const d = await call<Design>("app_design", { designId });
  app.design = d;
  app.selected = "rocket";
  remember(d.path);
  await refresh();
  if (app.page === "home") app.page = "design";
}

export async function openExample(name: string) {
  await run(async () => {
    const r = await call("open_design", { example: name });
    await load(r.designId);
  });
}

export async function openPath(path: string) {
  await run(async () => {
    const r = await call("open_design", { path });
    await load(r.designId);
    if (r.conversionWarnings?.length) toast("Converted on opening: " + r.conversionWarnings.join("; "));
  });
}

export async function openDialog() {
  try {
    const r = await call<{ path: string | null }>("app_choose_file", { mode: "open" });
    if (r.path) await openPath(r.path);
  } catch {
    const p = await ask("Open", "File (.ork or .rkt)");
    if (p) await openPath(p.trim());
  }
}

export async function newDesign() {
  const name = await ask("New design", "Rocket name", "Rocket");
  if (!name) return;
  await run(async () => {
    const r = await call("open_design", { newRocketName: name });
    await load(r.designId);
    app.page = "design";
  });
}

export async function save(as = false) {
  const d = app.design;
  if (!d) return;
  await run(async () => {
    let path = as || !d.path ? null : d.path;
    if (!path) {
      try {
        const r = await call<{ path: string | null }>("app_choose_file", { mode: "save", name: d.name + ".ork" });
        path = r.path;
      } catch {
        path = await ask("Save as", "File (.ork)", (d.path ?? d.name) + (d.path ? "" : ".ork"));
      }
      if (!path) return;
      if (!/\.ork$/i.test(path)) path += ".ork";
    }
    const r = await call("save_design", { designId: d.designId, path });
    toast("Saved " + r.saved);
    remember(r.saved);
    await refresh();
  });
}

let flightSeq = 0;

/** Re-reads the design, the 3-D model, the open editor, and (in the background) the quick flight. */
export async function refresh() {
  const d = app.design;
  if (!d) return;
  const id = d.designId;
  const [design, model] = await Promise.all([call<Design>("app_design", { designId: id }), call<Model>("app_model", { designId: id })]);
  app.design = design;
  app.model = model;
  if (!exists(design.tree, app.selected)) app.selected = "rocket";
  app.editor = await call<Editor>("app_component", { designId: id, component: app.selected });
  app.revision++;
  const seq = ++flightSeq;
  app.flying = true;
  call<QuickFlight>("app_quick_flight", { designId: id })
    .then((f) => seq === flightSeq && (app.flight = f))
    .catch((e) => seq === flightSeq && (app.flight = { error: e.message }))
    .finally(() => seq === flightSeq && (app.flying = false));
  if (autoCheck()) checkRules(600);
}

function exists(n: TreeNode, id: string): boolean {
  return n.id === id || n.children.some((c) => exists(c, id));
}

export function findNode(n: TreeNode | undefined, id: string): TreeNode | null {
  if (!n) return null;
  if (n.id === id) return n;
  for (const c of n.children) {
    const f = findNode(c, id);
    if (f) return f;
  }
  return null;
}

export function parentOf(n: TreeNode, id: string): TreeNode | null {
  for (const c of n.children) {
    if (c.id === id) return n;
    const p = parentOf(c, id);
    if (p) return p;
  }
  return null;
}

export async function select(id: string) {
  if (!app.design) return;
  app.selected = id;
  await run(async () => {
    app.editor = await call<Editor>("app_component", { designId: app.design!.designId, component: id });
  });
}

async function change(tool: string, args: Record<string, unknown>) {
  const d = app.design;
  if (!d) return undefined;
  return run(async () => {
    const r = await call(tool, { designId: d.designId, ...args });
    await refresh();
    return r;
  });
}

export async function editField(key: string, value: unknown) {
  return change("app_edit", { component: app.selected, key, value });
}

/** Where a new part of this type goes: inside the selection if it fits there, else after it, else further up. */
export function placement(type: string): { parent: string; index: number } | null {
  const tree = app.design?.tree;
  if (!tree) return null;
  let node: TreeNode | null = findNode(tree, app.selected) ?? tree;
  while (node) {
    if (node.accepts.includes(type)) return { parent: node.id, index: node.children.length };
    const p: TreeNode | null = parentOf(tree, node.id);
    if (p && p.accepts.includes(type)) return { parent: p.id, index: p.children.findIndex((c) => c.id === node!.id) + 1 };
    node = p;
  }
  return null;
}

export async function addPart(type: string) {
  const d = app.design;
  const at = placement(type);
  if (!d || !at) return;
  const r = await change("add_component", {
    type,
    parent: at.parent === "rocket" ? d.tree.name : at.parent,
    index: at.index,
    properties: {},
  });
  if (r?.id) await select(r.id);
}

export async function removeSelected() {
  if (!app.design || app.selected === "rocket") return;
  const tree = app.design.tree;
  const p = parentOf(tree, app.selected);
  await change("remove_component", { component: app.selected });
  if (p) await select(exists(app.design.tree, p.id) ? p.id : "rocket");
}

export async function move(by: number) {
  if (app.selected === "rocket") return;
  await change("app_move", { component: app.selected, by });
}

export async function duplicate() {
  if (app.selected === "rocket") return;
  const r = await change("app_duplicate", { component: app.selected });
  if (r?.id) await select(r.id);
}

export async function undo() {
  if (app.design?.canUndo) await change("undo", {});
}

export async function redo() {
  if (app.design?.canRedo) await change("redo", {});
}

export async function selectConfig(id: string) {
  await change("app_select_configuration", { configuration: id });
}

/** Any other engine action on the open design, followed by a refresh (motors, deployment, simulations...). */
export async function act(tool: string, args: Record<string, unknown>) {
  return change(tool, args);
}

export async function setSystem(s: "metric" | "imperial") {
  setUnitSystem(s);
  await call("app_set_units", { system: s }).catch(() => {});
}
