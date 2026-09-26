#!/usr/bin/env python3
"""Runs the real Codex CLI against the OpenRocket MCP server, with a local stand-in for the model.

The stand-in speaks the Responses API: it records the tools Codex offers, asks Codex to call three OpenRocket tools
(the last with a bad path, to check that errors come back readably) and then finishes. Needs `codex` on PATH and the
server built (./gradlew installDist).

    python3 scripts/test_codex.py
"""
import json, os, shutil, socket, subprocess, sys, tempfile, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BIN = os.path.join(ROOT, "build", "install", "openrocket-mcp", "bin",
                   "openrocket-mcp.bat" if os.name == "nt" else "openrocket-mcp")
PLAN = [("open_design", {"example": "Two stage"}), ("check_requirements", {}), ("open_design", {"path": "missing.ork"})]
requests = []


class Model(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        requests.append(body)
        i = len(requests) - 1
        if i < len(PLAN):
            name, args = PLAN[i]
            item = {"type": "function_call", "id": f"fc{i}", "call_id": f"call{i}", "namespace": "mcp__openrocket",
                    "name": name, "arguments": json.dumps(args)}
        else:
            item = {"type": "message", "role": "assistant", "id": f"m{i}",
                    "content": [{"type": "output_text", "text": "done"}]}
        events = [{"type": "response.created", "response": {"id": f"r{i}"}},
                  {"type": "response.output_item.done", "output_index": 0, "item": item},
                  {"type": "response.completed", "response": {"id": f"r{i}", "usage": {
                      "input_tokens": 1, "input_tokens_details": None, "output_tokens": 1,
                      "output_tokens_details": None, "total_tokens": 2}}}]
        data = "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in events).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def outputs():
    """Tool outputs Codex sent back to the model, by call id."""
    out = {}
    for it in requests[-1].get("input", []):
        if it.get("type") == "function_call_output":
            o = it["output"]
            out[it["call_id"]] = o if isinstance(o, str) else " ".join(p.get("text", "") for p in o)
    return out


def main():
    if not shutil.which("codex"):
        sys.exit("codex is not installed (npm i -g @openai/codex)")
    if not os.path.exists(BIN):
        sys.exit(f"Build first: ./gradlew installDist ({BIN} missing)")
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = HTTPServer(("127.0.0.1", port), Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    tmp = tempfile.mkdtemp()
    home, rockets = os.path.join(tmp, "codex"), os.path.join(tmp, "rockets")
    os.makedirs(home)
    os.makedirs(rockets)
    with open(os.path.join(home, "config.toml"), "w") as f:
        f.write(f"""model = "stand-in"
model_provider = "standin"
[model_providers.standin]
name = "stand-in"
base_url = "http://127.0.0.1:{port}/v1"
wire_api = "responses"
env_key = "STANDIN_KEY"
[mcp_servers.openrocket]
command = {json.dumps(BIN)}
startup_timeout_sec = 60
tool_timeout_sec = 300
[mcp_servers.openrocket.env]
OPENROCKET_MCP_WORKSPACE = {json.dumps(rockets)}
""")
    env = dict(os.environ, CODEX_HOME=home, STANDIN_KEY="x",
               NO_PROXY="127.0.0.1,localhost," + os.environ.get("NO_PROXY", ""))
    p = subprocess.run(["codex", "exec", "--skip-git-repo-check", "--dangerously-bypass-approvals-and-sandbox",
                        "Open the two stage example and check it"], cwd=rockets, env=env, stdin=subprocess.DEVNULL,
                       capture_output=True, text=True, timeout=600)
    server.shutdown()
    log = p.stdout + p.stderr
    assert p.returncode == 0, log[-3000:]
    offered = [t for t in requests[0].get("tools", []) if t.get("name") == "mcp__openrocket"]
    assert offered, "Codex did not offer the OpenRocket tools: " + json.dumps([t.get("name") for t in requests[0].get("tools", [])])
    names = {t["name"] for t in offered[0]["tools"]}
    assert {"open_design", "check_requirements", "compare_designs", "list_files"} <= names, sorted(names)
    out = outputs()
    assert '"designId":"d1"' in out.get("call0", ""), out
    assert "Launch Canada" in out.get("call1", ""), out
    assert "File not found" in out.get("call2", ""), out
    print(f"OK: Codex offered {len(names)} OpenRocket tools, opened a design, ran the rule check, and reported a bad path")


if __name__ == "__main__":
    main()
