#!/usr/bin/env python3
"""RocketMaxxing's assistant through the real Claude Code and Codex CLIs, with local stand-ins for the models.

Starts the installed app, connects the assistant to Claude Code, then to Codex, and has each check the open design and
change it (through the app's private MCP endpoint), continue the conversation on a second message, and be undone.
The stand-ins speak the Messages API (Claude Code) and the Responses API (Codex) and script the tool calls, so no
account or network is needed. Needs `claude` and `codex` on PATH and the app built (./gradlew rocketmaxxing).

    python3 scripts/test_app_agents.py
"""
import json, os, re, shutil, socket, subprocess, sys, tempfile, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "build", "install", "openrocket-mcp", "bin", "rocketmaxxing.bat" if os.name == "nt" else "rocketmaxxing")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def design_id(text):
    m = re.search(r"designId (d\d+)", text)
    return m.group(1) if m else "d1"


def plan(design, length):
    """Check where the design stands, then change the nose cone; the conversation's next message gets text only."""
    return [("design_status", {"designId": design}),
            ("edit_components", {"designId": design, "changes": [{"component": "Nose cone", "properties": {"length": length}}]})]


class Claude(BaseHTTPRequestHandler):
    """The Messages API as Claude Code uses it (streamed); side requests without the app's tools get a short text."""
    calls, requests = [0], []

    def log_message(self, *a):
        pass

    def do_GET(self):
        self.reply(200, "application/json", b"{}")

    def reply(self, code, kind, data):
        try:
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Claude Code gave up on a side request (a title, a topic check)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0) or b"{}") or b"{}")
        if "count_tokens" in self.path:
            return self.reply(200, "application/json", b'{"input_tokens": 10}')
        tools = [t.get("name", "") for t in body.get("tools", [])]
        content, stop = [{"type": "text", "text": "ok"}], "end_turn"
        if any(t.startswith("mcp__rocketmaxxing__") for t in tools):
            Claude.requests.append(body)
            i = Claude.calls[0]
            Claude.calls[0] += 1
            steps = plan(design_id(json.dumps(body["messages"][0])), "500 mm")
            if i < len(steps):
                content, stop = [{"type": "tool_use", "id": f"toolu_{i}", "name": "mcp__rocketmaxxing__" + steps[i][0],
                                  "input": steps[i][1]}], "tool_use"
            else:
                content = [{"type": "text", "text": "The nose cone is now 500 mm." if i == len(steps) else "Still 500 mm."}]
        events = [{"type": "message_start", "message": {"id": "msg", "type": "message", "role": "assistant",
                                                        "model": body.get("model", "x"), "content": [], "stop_reason": None,
                                                        "stop_sequence": None, "usage": {"input_tokens": 1, "output_tokens": 1}}}]
        for k, c in enumerate(content):
            if c["type"] == "text":
                events += [{"type": "content_block_start", "index": k, "content_block": {"type": "text", "text": ""}},
                           {"type": "content_block_delta", "index": k, "delta": {"type": "text_delta", "text": c["text"]}}]
            else:
                events += [{"type": "content_block_start", "index": k,
                            "content_block": {"type": "tool_use", "id": c["id"], "name": c["name"], "input": {}}},
                           {"type": "content_block_delta", "index": k,
                            "delta": {"type": "input_json_delta", "partial_json": json.dumps(c["input"])}}]
            events.append({"type": "content_block_stop", "index": k})
        events += [{"type": "message_delta", "delta": {"stop_reason": stop, "stop_sequence": None}, "usage": {"output_tokens": 5}},
                   {"type": "message_stop"}]
        if body.get("stream"):
            data = "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in events).encode()
            return self.reply(200, "text/event-stream", data)
        return self.reply(200, "application/json", json.dumps(dict(events[0]["message"], content=content, stop_reason=stop)).encode())


class Codex(BaseHTTPRequestHandler):
    """The Responses API as Codex uses it (streamed)."""
    requests = []

    def log_message(self, *a):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        Codex.requests.append(body)
        i = len(Codex.requests) - 1
        steps = plan(design_id(json.dumps(body.get("input", ""))), "450 mm")
        if i < len(steps):
            item = {"type": "function_call", "id": f"fc{i}", "call_id": f"call{i}", "namespace": "mcp__rocketmaxxing",
                    "name": steps[i][0], "arguments": json.dumps(steps[i][1])}
        else:
            item = {"type": "message", "role": "assistant", "id": f"m{i}",
                    "content": [{"type": "output_text", "text": "The nose cone is now 450 mm." if i == len(steps) else "Still 450 mm."}]}
        events = [{"type": "response.created", "response": {"id": f"r{i}"}},
                  {"type": "response.output_item.done", "output_index": 0, "item": item},
                  {"type": "response.completed", "response": {"id": f"r{i}", "usage": {
                      "input_tokens": 1, "input_tokens_details": None, "output_tokens": 1, "output_tokens_details": None,
                      "total_tokens": 2}}}]
        data = "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in events).encode()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass


def serve(handler):
    port = free_port()
    server = HTTPServer(("127.0.0.1", port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return port


class App:
    def __init__(self, env, workspace):
        port = free_port()
        self.url = f"http://127.0.0.1:{port}"
        self.proc = subprocess.Popen([APP, "--no-browser", "--port", str(port), "--workspace", workspace], env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.token = None
        start = time.time()
        while time.time() - start < 120:
            line = self.proc.stdout.readline()
            m = re.search(r"#token=([0-9a-f]+)", line)
            if m:
                self.token = m.group(1)
                break
        assert self.token, "the app did not start"
        threading.Thread(target=lambda: [None for _ in self.proc.stdout], daemon=True).start()
        self.id = 0

    def call(self, tool, args=None):
        self.id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self.id, "method": "tools/call", "params": {"name": tool, "arguments": args or {}}})
        req = urllib.request.Request(self.url + "/rpc", body.encode(), {"Authorization": "Bearer " + self.token,
                                                                         "Content-Type": "application/json"})
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=300) as r:
            res = json.loads(r.read())["result"]
        text = res["content"][0]["text"]
        assert not res.get("isError"), f"{tool}: {text}"
        return json.loads(text)

    def ask(self, text, design):
        start = len(self.call("app_assistant_poll", {"from": 0})["events"])
        self.call("app_assistant_send", {"text": text, "designId": design})
        deadline = time.time() + 300
        while time.time() < deadline:
            r = self.call("app_assistant_poll", {"from": start})
            if not r["running"]:
                return r["events"]
            time.sleep(0.5)
        raise AssertionError("no answer in 5 minutes")

    def nose_length(self, design):
        def find(n):
            if n["name"] == "Nose cone":
                return n["id"]
            for c in n["children"]:
                f = find(c)
                if f:
                    return f
        nose = find(self.call("app_design", {"designId": design})["tree"])
        for s in self.call("app_component", {"designId": design, "component": nose})["sections"]:
            for f in s["fields"]:
                if f["key"] == "length":
                    return round(f["value"], 4)


def check_turn(events, name, length):
    kinds = [e["kind"] for e in events]
    tools = [e for e in events if e["kind"] == "tool"]
    assert not [e for e in events if e["kind"] == "error"], f"{name}: {events}"
    assert [t["tool"] for t in tools] == ["design_status", "edit_components"], f"{name}: {events}"
    assert all(t["ok"] for t in tools), f"{name}: {tools}"
    assert not tools[0]["changed"] and tools[1]["changed"], f"{name}: only the edit changes the design: {tools}"
    assert kinds[-1] == "claude" and length in events[-1]["text"], f"{name}: {events}"


def main():
    for tool in ("claude", "codex"):
        if not shutil.which(tool):
            sys.exit(f"{tool} is not installed")
    if not os.path.exists(APP):
        sys.exit(f"Build first: ./gradlew rocketmaxxing ({APP} missing)")
    claude_port, codex_port = serve(Claude), serve(Codex)
    tmp = tempfile.mkdtemp()
    home, workspace, codex_home = os.path.join(tmp, "home"), os.path.join(tmp, "rockets"), os.path.join(tmp, "codex")
    for d in (home, workspace, codex_home):
        os.makedirs(d)
    with open(os.path.join(codex_home, "config.toml"), "w") as f:
        f.write(f"""model = "stand-in"
model_provider = "standin"
[model_providers.standin]
name = "stand-in"
base_url = "http://127.0.0.1:{codex_port}/v1"
wire_api = "responses"
env_key = "STANDIN_KEY"
""")
    # A clean environment, as when the app is started from the desktop: no session of another Claude Code around.
    env = {k: v for k, v in os.environ.items() if not k.startswith(("CLAUDE", "ANTHROPIC", "CODEX", "OPENAI"))}
    env.update(HOME=home, USERPROFILE=home, CODEX_HOME=codex_home, STANDIN_KEY="x", ANTHROPIC_API_KEY="sk-stand-in",
               ANTHROPIC_BASE_URL=f"http://127.0.0.1:{claude_port}", JAVA_OPTS=f"-Duser.home={home}",
               NO_PROXY="127.0.0.1,localhost," + os.environ.get("NO_PROXY", ""))
    app = App(env, workspace)
    try:
        design = app.call("open_design", {"example": "A simple model rocket"})["designId"]
        before = app.nose_length(design)
        app.call("app_assistant_settings", {"enabled": True, "provider": "claude-code"})
        found = {c["id"]: c for c in app.call("app_assistant_connections")["connections"]}
        assert found["claude-code"]["installed"] and found["codex"]["installed"], found

        check_turn(app.ask("Make the nose cone 500 mm long", design), "Claude Code", "500 mm")
        assert app.nose_length(design) == 0.5, app.nose_length(design)
        again = app.ask("Is it still 500 mm?", design)
        assert again[-1]["kind"] == "claude" and "Still" in again[-1]["text"], again
        assert len(Claude.requests[-1]["messages"]) > 2, "the second message continues the same Claude Code session"

        app.call("app_assistant_settings", {"provider": "codex"})
        check_turn(app.ask("Make the nose cone 450 mm long", design), "Codex", "450 mm")
        assert app.nose_length(design) == 0.45, app.nose_length(design)
        again = app.ask("Is it still 450 mm?", design)
        assert again[-1]["kind"] == "claude" and "Still" in again[-1]["text"], again
        assert len(Codex.requests[-1]["input"]) > len(Codex.requests[0]["input"]), "the second message continues the Codex thread"

        # Edits from either tool are ordinary undo steps.
        app.call("undo", {"designId": design})
        app.call("undo", {"designId": design})
        assert app.nose_length(design) == before, (app.nose_length(design), before)
        print(f"OK: Claude Code and Codex each checked and changed the design through the app ({len(Claude.requests)} "
              f"Claude Code and {len(Codex.requests)} Codex model requests), continued the conversation, and undo restored it")
    finally:
        app.proc.terminate()


if __name__ == "__main__":
    main()
