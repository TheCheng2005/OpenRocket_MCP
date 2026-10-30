"""Starts the installed RocketMaxxing app without a window and checks its local server end to end.

    ./gradlew rocketmaxxing && python3 scripts/test_rocketmaxxing.py
    python3 scripts/test_rocketmaxxing.py LAUNCHER [ARGS...]   # a packaged app instead, e.g.
        build/installers/RocketMaxxing.app/Contents/MacOS/RocketMaxxing
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BIN = os.path.join(ROOT, "build", "install", "openrocket-mcp", "bin", "rocketmaxxing" + (".bat" if os.name == "nt" else ""))


def main():
    cmd = sys.argv[1:] or [BIN]
    p = subprocess.Popen(cmd + ["--no-browser", "--port", "0", "--workspace", tempfile.mkdtemp()],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        url = None
        deadline = time.time() + 90
        while time.time() < deadline:
            line = p.stdout.readline()
            m = re.search(r"Open (http://127\.0\.0\.1:\d+)/#token=([0-9a-f]+)", line)
            if m:
                url, token = m.group(1), m.group(2)
                break
        assert url, "the app did not print its address"
        page = urllib.request.urlopen(url + "/").read().decode()
        assert "<title>RocketMaxxing</title>" in page, "the interface is not served (was it built?)"

        def call(tool, args=None):
            body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                               "params": {"name": tool, "arguments": args or {}}}).encode()
            req = urllib.request.Request(url + "/rpc", body, {"Authorization": "Bearer " + token, "Origin": url})
            r = json.loads(urllib.request.urlopen(req).read())["result"]
            text = r["content"][0]["text"]
            assert not r["isError"], tool + ": " + text
            return json.loads(text)

        session = call("app_session")
        assert len(session["examples"]) >= 10 and session["materials"]["BULK"], session
        d = call("open_design", {"example": "Two stage high power rocket"})["designId"]
        design = call("app_design", {"designId": d})
        assert design["stability"]["margin"] > 1, design["stability"]
        assert call("app_model", {"designId": d})["parts"], "no 3-D model"
        flight = call("app_quick_flight", {"designId": d})
        assert flight["apogee"] > 100, flight
        # OpenRocket's motor database and a rendered picture: the packaged runtime has what they need.
        motors = call("search_motors", {"limit": 3})
        assert motors["matches"] > 1000 and motors["motors"], motors
        assert call("draw_rocket", {"designId": d, "path": "rocket.svg"}), "no drawing"
        try:
            urllib.request.urlopen(urllib.request.Request(url + "/rpc", b"{}", {"Authorization": "Bearer wrong"}))
            raise AssertionError("a wrong token was accepted")
        except urllib.error.HTTPError as e:
            assert e.code == 401
        print(f"RocketMaxxing OK: {session['version']} (engine {session['engineVersion']}), "
              f"apogee {flight['apogee']:.0f} m, stability {design['stability']['margin']:.2f} cal")
    finally:
        p.kill()


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("FAILED:", e)
        sys.exit(1)
