#!/usr/bin/env python3
"""Unpacks a Claude Desktop extension (.mcpb) and starts it exactly as its manifest says, like Claude Desktop does.

    python3 scripts/test_mcpb.py build/distributions/openrocket-mcp-*.mcpb
"""
import glob, json, os, subprocess, sys, tempfile, zipfile

path = glob.glob(sys.argv[1])[0] if len(sys.argv) > 1 else glob.glob("build/distributions/*.mcpb")[0]
tmp = tempfile.mkdtemp()
ext, rockets = os.path.join(tmp, "ext"), os.path.join(tmp, "rockets")
os.makedirs(rockets)
with zipfile.ZipFile(path) as z:
    z.extractall(ext)
    for info in z.infolist():  # zipfile drops the executable bit; Claude Desktop keeps it
        mode = info.external_attr >> 16
        if mode:
            os.chmod(os.path.join(ext, info.filename), mode)
m = json.load(open(os.path.join(ext, "manifest.json")))
cfg = dict(m["server"]["mcp_config"])
cfg.update(cfg.get("platform_overrides", {}).get("win32" if os.name == "nt" else "", {}))
sub = lambda s: s.replace("${__dirname}", ext).replace("${user_config.workspace}", rockets).replace("${user_config.standards_file}", "")
cmd = [sub(cfg["command"])] + [sub(a) for a in cfg["args"]]
env = dict(os.environ, **{k: sub(v) for k, v in cfg["env"].items()})
env.pop("JAVA_HOME", None)
msgs = [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "open_design", "arguments": {"example": "Dual parachute"}}},
    {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "save_design", "arguments": {"path": "dual.ork"}}},
    {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "check_requirements", "arguments": {}}},
]
p = subprocess.run(cmd, input="\n".join(json.dumps(x) for x in msgs) + "\n", capture_output=True, text=True, env=env, timeout=600)
out = {r["id"]: r for r in map(json.loads, p.stdout.splitlines())}
assert len(out) == 5, p.stdout + p.stderr
names = {t["name"] for t in out[2]["result"]["tools"]}
assert names == {t["name"] for t in m["tools"]}, "manifest tool list is out of date"
for i in (3, 4, 5):
    assert not out[i]["result"]["isError"], out[i]
assert os.path.exists(os.path.join(rockets, "dual.ork")), "designs are saved in the rocket folder"
print(f"OK: {os.path.basename(path)} starts on its bundled Java, {len(names)} tools, simulation and rule check run")
