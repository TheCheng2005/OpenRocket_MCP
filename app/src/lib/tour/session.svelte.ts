// A guided tour: one step at a time, each pointing at a part of the screen. Which tours were seen is kept, so a page
// offers its tour once.

import { isOpen, toggle } from "../panels.svelte";

export interface Step {
  title: string;
  text: string;
  /** What to point at (a CSS selector); none: a card in the middle of the screen. */
  target?: string;
  /** A panel to open first: [id, whether it starts open]. */
  open?: [string, boolean];
  /** Buttons on the last step. */
  actions?: { label: string; run: () => void }[];
}

export const tour = $state({ name: null as string | null, steps: [] as Step[], at: 0 });

/** Panels the tour opened, folded again at the end so the page is left as it was. */
let opened: [string, boolean][] = [];

const SEEN = "rocketmaxxing.tours";

function seenList(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(SEEN) ?? "[]");
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

export function seen(name: string): boolean {
  return seenList().includes(name);
}

export function markSeen(name: string) {
  try {
    localStorage.setItem(SEEN, JSON.stringify([...new Set([...seenList(), name])]));
  } catch {
    /* offered again next time: fine */
  }
}

export function start(name: string, steps: Step[]) {
  markSeen(name);
  tour.name = name;
  tour.steps = steps;
  go(0);
}

export function go(i: number) {
  const step = tour.steps[i];
  if (!step) return end();
  if (step.open && !isOpen(step.open[0], step.open[1])) {
    toggle(step.open[0], step.open[1]);
    opened.push(step.open);
  }
  tour.at = i;
}

export function end() {
  for (const [id, initial] of opened) if (isOpen(id, initial)) toggle(id, initial);
  opened = [];
  tour.name = null;
  tour.steps = [];
  tour.at = 0;
}
