// Shortcut names as each system writes them: ⌘S and ⇧⌘S on a Mac, Ctrl+S and Ctrl+Shift+S elsewhere.

export const MAC = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

export function keys(key: string, shift = false): string {
  return MAC ? (shift ? "⇧⌘" : "⌘") + key : "Ctrl+" + (shift ? "Shift+" : "") + key;
}
