// Pictures and videos the engine makes go into the designs folder's exports/ folder, named after the design.

import { app } from "../state.svelte";

export function exportPath(suffix: string): string {
  const name = (app.design?.name ?? "rocket").replace(/[^A-Za-z0-9._-]+/g, "_").replace(/^_+|_+$/g, "") || "rocket";
  return `exports/${name}-${suffix}`;
}

/** "…/maple-flight.gif (3.8 MB)" -> the path. */
export function pathOf(v: unknown): string | null {
  return typeof v === "string" && v ? v.replace(/\s+\([^()]*\)\s*$/, "") : null;
}

export function sizeOf(v: unknown): string {
  const m = typeof v === "string" ? v.match(/\(([^()]*)\)\s*$/) : null;
  return m ? m[1] : "";
}
