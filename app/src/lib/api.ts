// The engine client: every action is a tool call to the local RocketMaxxing server (the same tools the MCP server
// offers Claude). The token comes from the address the launcher opened and is never sent anywhere else.

const KEY = "rocketmaxxing.token";

function readToken(): string {
  const hash = new URLSearchParams(location.hash.replace(/^#/, ""));
  const t = hash.get("token");
  if (t) {
    try {
      sessionStorage.setItem(KEY, t);
    } catch {
      /* private mode: keep it in memory only */
    }
    history.replaceState(null, "", location.pathname + location.search);
    return t;
  }
  try {
    return sessionStorage.getItem(KEY) ?? "";
  } catch {
    return "";
  }
}

const token = readToken();
let nextId = 1;

export class ToolError extends Error {}

/** JSON as the engine writes it; a bare NaN or Infinity in a value position (never valid JSON) reads as null. */
function parse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return JSON.parse(text.replace(/([:\[,]\s*)-?(?:NaN|Infinity)(?=\s*[,\]}])/g, "$1null"));
  }
}

async function rpc(method: string, params: unknown): Promise<any> {
  let res: Response;
  try {
    res = await fetch("rpc", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ jsonrpc: "2.0", id: nextId++, method, params }),
    });
  } catch {
    throw new ToolError("The RocketMaxxing engine is not running. Start the app again.");
  }
  if (res.status === 401) {
    throw new ToolError("This window is not connected to the engine. Close it and start RocketMaxxing again.");
  }
  if (!res.ok) {
    throw new ToolError(`The engine answered ${res.status}.`);
  }
  const msg = await res.json();
  if (msg.error) {
    throw new ToolError(msg.error.message ?? "Engine error");
  }
  return msg.result;
}

/** Calls a tool; returns its JSON result (or text), or throws ToolError with the engine's explanation. */
export async function call<T = any>(name: string, args: Record<string, unknown> = {}): Promise<T> {
  const result = await rpc("tools/call", { name, arguments: args });
  const text: string = result?.content?.[0]?.text ?? "";
  if (result?.isError) {
    throw new ToolError(text.replace(/^Error:\s*/, ""));
  }
  try {
    return parse(text) as T;
  } catch {
    return text as unknown as T;
  }
}

/** Tells the launcher the window is still open (it stops the engine once the window has been closed a while). */
export function keepAlive(): void {
  const beat = () => rpc("ping", {}).catch(() => {});
  beat();
  setInterval(beat, 20_000);
}

export const connected = token !== "";
