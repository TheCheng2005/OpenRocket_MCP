// Units: the engine works in SI; the app shows and reads numbers in the unit chosen for each quantity
// (metric or imperial by default, any unit per quantity like OpenRocket's preferences).

export type Dim =
  | "LENGTH" | "DISTANCE" | "VELOCITY" | "ACCELERATION" | "MASS" | "FORCE" | "AREA" | "VOLUME" | "PRESSURE"
  | "DENSITY" | "TIME" | "ANGLE" | "IMPULSE" | "TEMPERATURE";

export interface UnitDef {
  unit: string;
  factor: number;
  offset: number;
}

interface DimDef {
  metric: string;
  imperial: string;
  units: UnitDef[];
}

const STORE = "rocketmaxxing.units";

function load(): { system: "metric" | "imperial"; chosen: Record<string, string> } {
  try {
    const s = JSON.parse(localStorage.getItem(STORE) ?? "");
    if (s && (s.system === "metric" || s.system === "imperial")) {
      return { system: s.system, chosen: s.chosen ?? {} };
    }
  } catch {
    /* first start, or storage unavailable */
  }
  return { system: "metric", chosen: {} };
}

export const units = $state({ ...load(), dims: {} as Record<string, DimDef> });

function save() {
  try {
    localStorage.setItem(STORE, JSON.stringify({ system: units.system, chosen: units.chosen }));
  } catch {
    /* not kept: fine */
  }
}

export function setSystem(system: "metric" | "imperial") {
  units.system = system;
  units.chosen = {};
  save();
}

export function choose(dim: Dim, unit: string) {
  units.chosen[dim] = unit;
  save();
}

export function unitOf(dim: Dim): UnitDef {
  const d = units.dims[dim];
  if (!d) {
    return { unit: "", factor: 1, offset: 0 };
  }
  const name = units.chosen[dim] ?? (units.system === "imperial" ? d.imperial : d.metric);
  return d.units.find((u) => u.unit === name) ?? d.units[0];
}

export function choices(dim: Dim): UnitDef[] {
  return units.dims[dim]?.units ?? [];
}

export function toShown(si: number, dim: Dim | undefined | null, unit?: UnitDef): number {
  if (!dim) return si;
  const u = unit ?? unitOf(dim);
  return (si - u.offset) / u.factor;
}

export function toSi(v: number, dim: Dim | undefined | null, unit?: UnitDef): number {
  if (!dim) return v;
  const u = unit ?? unitOf(dim);
  return v * u.factor + u.offset;
}

/** A number for reading: about four significant digits, no trailing zeros. */
export function num(v: number | null | undefined, digits = 4): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return "–";
  if (v === 0) return "0";
  const a = Math.abs(v);
  if (a >= 10 ** digits) return Math.round(v).toLocaleString("en-US");
  const decimals = Math.max(0, digits - 1 - Math.floor(Math.log10(a)));
  return Number(v.toFixed(Math.min(decimals, 6))).toLocaleString("en-US", { maximumFractionDigits: 6 });
}

const PRETTY: Record<string, string> = { "m/s2": "m/s²", "ft/s2": "ft/s²", m2: "m²", cm2: "cm²", in2: "in²", ft2: "ft²",
  cm3: "cm³", in3: "in³", "kg/m3": "kg/m³", "g/cm3": "g/cm³", "lb/ft3": "lb/ft³", deg: "°", C: "°C", F: "°F", Ns: "N·s",
  "lbf-s": "lbf·s" };

export function pretty(unit: string): string {
  return PRETTY[unit] ?? unit;
}

/** "123.4 mm" in the chosen unit. */
export function fmt(si: number | null | undefined, dim: Dim | undefined | null, digits = 4): string {
  if (si === null || si === undefined || !Number.isFinite(si)) return "–";
  if (!dim) return num(si, digits);
  const u = unitOf(dim);
  const unit = pretty(u.unit);
  return num(toShown(si, dim, u), digits) + (unit === "°" ? "°" : " " + unit);
}

/** Number and unit apart, for big readouts: ["2,998", "m"]. */
export function split(si: number | null | undefined, dim: Dim | undefined | null, digits = 4): [string, string] {
  if (si === null || si === undefined || !Number.isFinite(si)) return ["–", ""];
  if (!dim) return [num(si, digits), ""];
  const u = unitOf(dim);
  return [num(toShown(si, dim, u), digits), pretty(u.unit)];
}
