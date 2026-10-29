// The optional assistant: Claude with the team's own API key, working through the engine's tools. The engine runs the
// conversation; the page sends messages and polls for what happened (Claude's words, each tool it used).

import { call } from "../api";
import { app, refresh, run } from "../state.svelte";

export type Provider = "api" | "claude-code" | "codex";

export interface AssistantSettings {
  enabled: boolean;
  provider: Provider;
  model: string;
  models: string[];
  claudeCodeModel: string;
  claudeCodeModels: string[];
  codexModel: string;
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

/** Claude Code or Codex on this computer, from their own status commands. */
export interface Connection {
  id: Provider;
  label: string;
  installed: boolean;
  path?: string;
  version?: string;
  signedIn?: boolean;
  detail?: string;
}

export const PROVIDER_NAMES: Record<Provider, string> = {
  "claude-code": "Claude Code",
  codex: "Codex",
  api: "Anthropic API",
};

export const CLAUDE_CODE_MODELS: Record<string, string> = {
  "": "Claude Code's default",
  opus: "Opus (most capable)",
  sonnet: "Sonnet (faster)",
  haiku: "Haiku (fastest)",
};

export const MODEL_NAMES: Record<string, string> = {
  "claude-opus-5-5": "Claude Opus 5.5 (most capable)",
  "claude-sonnet-5-5": "Claude Sonnet 5.5 (faster, lower cost)",
  "claude-haiku-5-5": "Claude Haiku 5.5 (fastest, lowest cost)",
};

export const assistant = $state({
  settings: null as AssistantSettings | null,
  connections: null as Connection[] | null,
  checking: false,
  open: false,
  events: [] as AssistantEvent[],
  running: false,
});

let next = 0;
let timer: ReturnType<typeof setTimeout> | undefined;

export async function loadSettings() {
  assistant.settings = await call<AssistantSettings>("app_assistant").catch(() => null);
}

/** Asks Claude Code and Codex whether they are installed and signed in (takes a few seconds). */
export async function checkConnections() {
  assistant.checking = true;
  try {
    assistant.connections = (await call<{ connections: Connection[] }>("app_assistant_connections")).connections;
  } catch {
    assistant.connections = [];
  } finally {
    assistant.checking = false;
  }
}

/** Who the assistant is in the drawer and the top bar. */
export function who(): string {
  const s = assistant.settings;
  return s?.provider === "codex" ? "Codex" : "Claude";
}

export async function configure(change: {
  enabled?: boolean;
  provider?: Provider;
  model?: string;
  claudeCodeModel?: string;
  codexModel?: string;
  key?: string;
  forgetKey?: boolean;
}) {
  await run(async () => {
    assistant.settings = await call<AssistantSettings>("app_assistant_settings", change);
    if (!assistant.settings?.enabled) assistant.open = false;
    if (change.provider) await pollOnce();
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

/** Picks up a note the engine added (switching connection starts a new conversation). */
async function pollOnce() {
  const r = await call<{ events: AssistantEvent[]; next: number }>("app_assistant_poll", { from: next }).catch(() => null);
  if (r) {
    next = r.next;
    assistant.events.push(...r.events);
  }
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
