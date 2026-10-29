// Which panels and sections are open, kept between sessions.

const KEY = "rocketmaxxing.panels";

function load(): Record<string, boolean> {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? "{}") ?? {};
  } catch {
    return {};
  }
}

const panels = $state<Record<string, boolean>>(load());

export function isOpen(id: string, initial = true): boolean {
  return panels[id] ?? initial;
}

export function toggle(id: string, initial = true) {
  panels[id] = !isOpen(id, initial);
  try {
    localStorage.setItem(KEY, JSON.stringify(panels));
  } catch {
    /* not kept */
  }
}
