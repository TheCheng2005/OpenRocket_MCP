// Appearance preference: follow the system, or always light / dark.

export type Theme = "system" | "light" | "dark";

const KEY = "rocketmaxxing.theme";

function load(): Theme {
  try {
    const t = localStorage.getItem(KEY);
    return t === "light" || t === "dark" ? t : "system";
  } catch {
    return "system";
  }
}

export const prefs = $state({ theme: load() });

export function applyTheme() {
  if (prefs.theme === "system") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = prefs.theme;
}

export function setTheme(t: Theme) {
  prefs.theme = t;
  try {
    if (t === "system") localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, t);
  } catch {
    /* not kept */
  }
  applyTheme();
}
