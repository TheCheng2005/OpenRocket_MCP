// Matching for the search: every word typed must be found in an entry, its name counting most, then the words people
// use for it (FEA for fin stress, chute for parachute), then its description. A word one letter off still matches a
// name or keyword ("fluter" finds Fin flutter).

export type Kind = "analysis" | "page" | "action" | "add" | "part" | "setting";

export interface Entry {
  kind: Kind;
  title: string;
  /** One line under the title: what it does, or where it goes. */
  sub?: string;
  /** Words people search with that are not in the title. */
  keys?: string;
  /** Longer text that may hold the word (an analysis's guide). */
  text?: string;
  /** Why it cannot be used now, shown instead of running it. */
  off?: string;
  /** A keyboard shortcut to show. */
  shortcut?: string;
  run: () => void;
}

export function norm(s: string): string {
  return s
    .toLowerCase()
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9.+]+/g, " ")
    .trim();
}

export function words(query: string): string[] {
  return norm(query).split(" ").filter(Boolean);
}

/** True when a and b differ by at most one letter (inserted, removed or changed). */
function near(a: string, b: string): boolean {
  if (Math.abs(a.length - b.length) > 1) return false;
  let i = 0, j = 0, edits = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      i++;
      j++;
      continue;
    }
    if (++edits > 1) return false;
    if (a.length > b.length) i++;
    else if (a.length < b.length) j++;
    else {
      i++;
      j++;
    }
  }
  return edits + (a.length - i) + (b.length - j) <= 1;
}

interface Prepared {
  title: string;
  keys: string;
  text: string;
  vocab: string[];
}

const CACHE = new WeakMap<Entry, Prepared>();

function prepared(e: Entry): Prepared {
  let p = CACHE.get(e);
  if (!p) {
    const title = norm(e.title), keys = norm(e.keys ?? "");
    p = { title, keys, text: norm((e.sub ?? "") + " " + (e.text ?? "")), vocab: (title + " " + keys).split(" ") };
    CACHE.set(e, p);
  }
  return p;
}

/** How well an entry matches the words typed; 0 when one of them is missing. */
export function score(e: Entry, ws: string[]): number {
  const p = prepared(e);
  let total = 0;
  for (const w of ws) {
    let s = 0;
    if (p.title.startsWith(w)) s = 100;
    else if ((" " + p.title).includes(" " + w)) s = 70;
    else if (p.title.includes(w)) s = 50;
    else if ((" " + p.keys).includes(" " + w)) s = 45;
    else if (p.keys.includes(w)) s = 30;
    else if ((" " + p.text).includes(" " + w)) s = 15;
    else if (w.length >= 4 && p.text.includes(w)) s = 8;
    else if (w.length >= 4 && p.vocab.some((v) => near(v, w))) s = 20;
    else return 0;
    total += s;
  }
  return total;
}

/** The best matches, best first (ties keep the given order). */
export function search(entries: Entry[], query: string, limit = 40): Entry[] {
  const ws = words(query);
  if (!ws.length) return entries.slice(0, limit);
  return entries
    .map((e, i) => ({ e, i, s: score(e, ws) }))
    .filter((x) => x.s > 0)
    .sort((a, b) => b.s - a.s || a.i - b.i)
    .slice(0, limit)
    .map((x) => x.e);
}
