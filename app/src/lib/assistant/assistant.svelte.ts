// The optional assistant: Claude with the team's own API key, working through the engine's tools. The engine runs the
// conversation; the page sends messages and polls for what happened (Claude's words, each tool it used).

import { call } from "../api";
import { app, refresh, run } from "../state.svelte";

export interface AssistantSettings {
  enabled: boolean;
  model: string;
  models: string[];
  hasKey: boolean;
  keySource: "saved" | "environment" | null;
  settingsFile: string;
}

export interface AssistantEvent {
  kind: "you" | "claude" | "tool" | "error" | "note";
  text?: string;
  tool?: string;
  title?: string;
  input?: string;
  ok?: boolean;
  changed?: boolean;
  result?: string;
  images?: string[];
}

export const MODEL_NAMES: Record<string, string> = {
  "claude-opus-5-5": "Claude Opus 5.5 (most capable)",
  "claude-sonnet-5-5": "Claude Sonnet 5.5 (faster, lower cost)",
  "claude-haiku-5-5": "Claude Haiku 5.5 (fastest, lowest cost)",
};

export const assistant = $state({
  settings: null as AssistantSettings | null,
  open: false,
  events: [] as AssistantEvent[],
  running: false,
});

let next = 0;
let timer: ReturnType<typeof setTimeout> | undefined;

export async function loadSettings() {
  assistant.settings = await call<AssistantSettings>("app_assistant").catch(() => null);
}

export async function configure(change: { enabled?: boolean; model?: string; key?: string; forgetKey?: boolean }) {
  await run(async () => {
    assistant.settings = await call<AssistantSettings>("app_assistant_settings", change);
    if (!assistant.settings?.enabled) assistant.open = false;
  });
}

export async function send(text: string) {
  const ok = await run(async () => {
    await call("app_assistant_send", { text, ...(app.design ? { designId: app.design.designId } : {}) });
    return true;
  });
  if (ok) {
    assistant.running = true;
    poll();
  }
}

export async function stop() {
  await call("app_assistant_stop").catch(() => {});
}

export async function reset() {
  await run(async () => {
    await call("app_assistant_reset");
    assistant.events = [];
    next = 0;
  });
}

/** Reads what happened since the last look; the design is refreshed when a tool changed it, and at the end. */
async function poll() {
  clearTimeout(timer);
  try {
    const r = await call<{ events: AssistantEvent[]; running: boolean; next: number }>("app_assistant_poll", { from: next });
    next = r.next;
    assistant.events.push(...r.events);
    const changed = r.events.some((e) => e.changed);
    assistant.running = r.running;
    if (changed || !r.running) await refresh().catch(() => {});
  } catch {
    assistant.running = false;
  }
  if (assistant.running) timer = setTimeout(poll, 700);
}
