#!/usr/bin/env python3
"""Scenario benchmark for openrocket-mcp.

Drives a fresh server over stdio (exactly like Claude Code does) through realistic student-team requests, checks
each answer against engineering expectations, and times every call.

    ./gradlew installDist && python3 scripts/benchmark.py [--verbose]

Exit code 0 when every check passes.
"""
import json
import os
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BIN = os.path.join(ROOT, "build", "install", "openrocket-mcp", "bin",
                   "openrocket-mcp.bat" if os.name == "nt" else "openrocket-mcp")
VERBOSE = "--verbose" in sys.argv


class Server:
    def __init__(self):
        env = dict(os.environ, OPENROCKET_MCP_STANDARDS="")
        self.p = subprocess.Popen([BIN], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  text=True, cwd=tempfile.mkdtemp(), env=env)
        self.id = 0
        self.calls = []
        self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                "clientInfo": {"name": "benchmark", "version": "1"}})

    def rpc(self, method, params):
        self.id += 1
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.id, "method": method, "params": params}) + "\n")
        self.p.stdin.flush()
        return json.loads(self.p.stdout.readline())

    def call(self, tool, args=None, expect_error=False):
        t0 = time.time()
        r = self.rpc("tools/call", {"name": tool, "arguments": args or {}})["result"]
        dt = time.time() - t0
        text = r["content"][0]["text"]
        self.calls.append((tool, dt, r["isError"], len(text)))
        if VERBOSE:
            print(f"  -> {tool} ({dt:.2f}s){' ERROR' if r['isError'] else ''}\n{text[:1500]}\n")
        if r["isError"] and not expect_error:
            raise AssertionError(f"{tool} failed: {text}")
        if expect_error:
            return text
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return text

    def close(self):
        self.p.stdin.close()
        self.p.wait(timeout=10)


def num(s):
    """Leading number of a formatted quantity such as '3047 m (9997 ft)'."""
    return float(str(s).split()[0])


def ft(s):
    """Feet value from '3047 m (9997 ft)'."""
    s = str(s)
    return float(s[s.index("(") + 1:].split()[0]) if "(" in s else num(s) / 0.3048


RESULTS = []


def check(scenario, name, ok, detail=""):
    RESULTS.append((scenario, name, bool(ok), detail))
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" - {detail}" if detail and (not ok or VERBOSE) else ""))


def scenario_from_scratch(s):
    """'Design a 4 in dual-deploy rocket for 10,000 ft that passes Launch Canada rules.'"""
    sc = "from-scratch 10k ft"
    d = s.call("open_design", {"newRocketName": "Bench 10k"})["designId"]
    stage = s.call("get_design", {"designId": d})["components"][0].split("[")[1][:8]
    add = lambda parent, typ, name, props: s.call("add_component", {"designId": d, "parent": parent, "type": typ,
                                                                    "name": name, "properties": props})
    add(stage, "NoseCone", "Nose cone", {"shapeType": "OGIVE", "length": "18 in", "aftDiameter": "4.02 in",
                                          "thickness": "2.5 mm", "material": "Fiberglass",
                                          "aftShoulderLength": "4 in", "aftShoulderDiameter": "3.9 in"})
    add(stage, "BodyTube", "Upper airframe", {"length": "24 in", "outerDiameter": "4.02 in", "thickness": "2 mm",
                                              "material": "Fiberglass"})
    add(stage, "BodyTube", "Lower airframe", {"length": "40 in", "outerDiameter": "4.02 in", "thickness": "2 mm",
                                              "material": "Fiberglass"})
    add("Lower airframe", "InnerTube", "MMT", {"length": "30 in", "outerDiameter": "78 mm", "thickness": "1.5 mm",
                                              "axialMethod": "BOTTOM", "axialOffset": 0})
    # Student-style property names: sweepLength and span must work; a bad property must not leave a half-built part.
    err = s.call("add_component", {"designId": d, "parent": "Lower airframe", "type": "TrapezoidFinSet",
                                   "name": "Broken", "properties": {"finCount": 4, "bogus": 1}}, expect_error=True)
    tree = json.dumps(s.call("get_design", {"designId": d})["components"])
    check(sc, "failed add_component leaves nothing behind", "Broken" not in tree and "Nothing was added" in err)
    fins = add("Lower airframe", "TrapezoidFinSet", "Fins", {"finCount": 4, "rootChord": "9 in", "tipChord": "3 in",
                                                             "span": "5 in", "sweepLength": "5 in",
                                                             "thickness": "3.2 mm", "material": "Fiberglass",
                                                             "axialMethod": "BOTTOM", "axialOffset": 0})
    applied = " ".join(fins["applied"])
    check(sc, "sweep length accepts inches (not an angle)", "sweep = 127 mm" in applied, applied)
    check(sc, "material set by name", "material = Fiberglass" in applied, applied)
    add("Upper airframe", "Parachute", "Main", {"diameter": "60 in", "cd": 0.8})
    add("Lower airframe", "Parachute", "Drogue", {"diameter": "18 in", "cd": 0.8})
    add("Upper airframe", "MassComponent", "Avionics", {"mass": "1.2 kg", "length": "8 in"})

    t0 = time.time()
    rank = s.call("rank_motors", {"designId": d, "mount": "MMT", "objective": "target_apogee",
                                  "targetApogee": "10000 ft", "minClass": "K"})
    check(sc, "rank_motors works on a design without a flight configuration", "createdConfiguration" in rank)
    rows = rank["ranking"]
    names = [r["motor"] for r in rows]
    check(sc, "no duplicate motors in ranking", len(names) == len(set(names)), str(names))
    check(sc, "all ranked motors fit the 30 in mount (+50 mm)", all(num(r["length"]) <= 812 for r in rows))
    check(sc, "chutes left on the motor ejection event are warned about", "warning" in rank and "ejection" in rank["warning"],
          rank.get("warning", ""))

    s.call("set_deployment", {"designId": d, "component": "Drogue", "event": "apogee", "configuration": "all"})
    s.call("set_deployment", {"designId": d, "component": "Main", "event": "altitude", "altitude": "1000 ft",
                              "configuration": "all"})
    rank = s.call("rank_motors", {"designId": d, "mount": "MMT", "objective": "target_apogee",
                                  "targetApogee": "10000 ft", "minClass": "K"})
    top = rank["ranking"][0]
    check(sc, "best motor is within 5% of 10,000 ft", abs(ft(top["apogee"].split(" (+")[0].split(" (-")[0]) - 10000) < 500,
          f'{top["motor"]}: {top["apogee"]}')
    check(sc, "rule-compliant motors ranked first", top["meetsRules"] == "yes" or "0 meet the rules" in rank["candidates"],
          top["meetsRules"])
    check(sc, "ranking reports stability with each motor", "minAscentStability" in top)
    check(sc, "motor ranking time < 30 s", time.time() - t0 < 30, f"{time.time() - t0:.1f} s")

    s.call("set_motor", {"designId": d, "mount": "MMT", "motor": top["motor"]})
    fins = [{"component": "Fins", "property": "height", "min": "3.5 in", "max": "8 in"},
            {"component": "Fins", "property": "rootChord", "min": "7 in", "max": "12 in"}]
    tight = s.call("optimize", {"designId": d, "objective": "max_apogee", "minStability": 1.6, "maxStability": 4.5,
                                "variables": fins, "maxEvaluations": 48})
    check(sc, "an infeasible stability window is explained (which limits bind)",
          tight["feasible"] or ("stability" in tight.get("note", "")), tight.get("note", "feasible"))
    opt = s.call("optimize", {"designId": d, "objective": "max_apogee", "meetRules": True,
                              "variables": fins, "maxEvaluations": 48, "apply": True})
    check(sc, "optimizer meets the rule-derived stability window (incl. 10% body length, design wind)", opt["feasible"],
          opt.get("note", json.dumps(opt["best"])))
    check(sc, "rule-derived stability floor includes the 2027 %-body-length edict",
          "body length" in opt["constraints"].get("fromRules", ""), opt["constraints"].get("fromRules", ""))
    check(sc, "optimizer evaluates the design-wind case", "evaluatedAt" in opt["constraints"])
    # Mach > 1 on 1/8 in fins: the flutter screen should object, and its suggested thickness should fix it.
    fl = s.call("fin_flutter", {"designId": d})["finSets"][0]
    check(sc, "flutter screen flags thin fins at Mach > 1", fl["status"] != "PASS", fl["status"])
    if "toReachRequiredMargin" in fl:
        need_in = num(fl["toReachRequiredMargin"]["thickness"].split("(")[1]) if "(" in fl["toReachRequiredMargin"]["thickness"] \
            else num(fl["toReachRequiredMargin"]["thickness"]) / 25.4
        sheet = next(t for t in [0.125, 0.1875, 0.25, 0.3125, 0.375, 0.5, 0.625, 0.75] if t >= need_in)  # stock G10 sheet
        s.call("edit_components", {"designId": d, "changes": [{"component": "Fins", "properties": {"thickness": f"{sheet} in"}}]})
        fl = s.call("fin_flutter", {"designId": d})["finSets"][0]
        check(sc, f"suggested fin thickness ({sheet} in stock) passes the flutter screen", fl["status"] == "PASS",
              fl["minMargin"])
        s.call("optimize", {"designId": d, "objective": "max_apogee", "meetRules": True, "variables": fins,
                            "maxEvaluations": 32, "apply": True})
    req = s.call("check_requirements", {"designId": d})
    fails = [c for c in req["checks"] if c["status"] == "FAIL"]
    check(sc, "final design has no rule failures", not fails, json.dumps(fails)[:600])
    items = " ".join(c["item"] for c in req["checks"])
    check(sc, "2027 edicts checked: L:D, damping ratio, % body length, pop tests",
          all(k in items for k in ["Length-to-diameter", "Damping ratio", "% of body length", "dress rehearsal"]), items[:300])
    check(sc, "manual checklist (electronics, radio, structures) included", len(req.get("manualChecks", [])) >= 10)
    return d


def scenario_recovery(s):
    """'Size our main for 20 ft/s and check shock loads, pins and charges.' (OpenRocket two-stage example)"""
    sc = "recovery chain"
    d = s.call("open_design", {"example": "Two stage high power"})["designId"]
    size = s.call("size_parachute", {"designId": d, "device": "Sustainer Main", "targetDescentRate": "20 ft/s"})
    m = size["presetMatches"]
    check(sc, "real parachutes suggested for 20 ft/s", len(m) > 0 and abs(ft(m[0]["descentRate"]) - 20) < 3,
          m[0]["descentRate"] if m else "none")
    rec = s.call("recovery_analysis", {"designId": d, "pinType": "4-40 nylon"})
    deps = rec["deployments"]
    drogue = [x for x in deps if x["role"] == "drogue"]
    mains = [x for x in deps if x["role"].startswith("main")]
    check(sc, "pins sized only for bays that must stay closed (drogue event)",
          all("shearPinsForLaterBays" in x for x in drogue) and not any("shearPinsForLaterBays" in x for x in mains))
    check(sc, "harness working load given for every deployment", all("harnessWorkingLoad" in x for x in deps))
    sweep = s.call("deployment_delay_sweep", {"designId": d, "device": "Sustainer Drogue", "delays": [0, 1, 2, 3, 4],
                                              "pinType": "4-40 nylon", "pinCount": 4})
    check(sc, "late-deployment study returns a latest safe delay", "latestDelayWithinCapacity" in sweep)
    bp = s.call("ejection_charge", {"bayDiameter": "4 in", "bayLength": "10 in", "pressure": "15 psi", "safetyFactor": 1})
    check(sc, "BP charge matches 0.006 D^2 L rule (~0.97 g)", abs(num(bp["primaryCharge"]) - 0.97) < 0.02, bp["primaryCharge"])
    shock = s.call("opening_shock", {"mass": 50, "velocity": 36, "cd": 2.2, "area": 0.636, "airDensity": 0.86, "cx": 1.4})
    check(sc, "opening shock reproduces team doc (~1100 N)", abs(num(shock["infiniteMassLoad"]) - 1092) < 5,
          shock["infiniteMassLoad"])


def scenario_two_stage(s):
    """'Is our sustainer stable after separation, and what tilt lockout / inhibit altitude do we program?'"""
    sc = "two-stage"
    d = s.call("open_design", {"example": "Two stage high power"})["designId"]
    g = s.call("get_design", {"designId": d})
    check(sc, "stability reported for sustainer alone", any("after" in st["when"] for st in g["stability"]))
    req = s.call("check_requirements", {"designId": d, "launchAngle": "6 deg"})
    items = [c["item"] for c in req["checks"]]
    tilt = [c for c in req["checks"] if c["item"].startswith("Tilt at upper-stage ignition")]
    check(sc, "tilt at air start checked (< 15 deg)", tilt and num(tilt[0]["value"]) < 15, tilt[0]["value"] if tilt else items)
    check(sc, "air-start altitude inhibit given", any("altitude inhibit" in i for i in items))
    check(sc, "booster and sustainer thrust-to-weight checked", any("Booster thrust-to-weight" in i for i in items)
          and any("Upper-stage thrust-to-weight" in i for i in items))


def scenario_custom_engine(s, tmp):
    """'Here is our liquid engine's static-fire curve; how high does Discovery go?'"""
    sc = "custom liquid engine"
    d = s.call("open_design", {"example": "Dual parachute"})["designId"]
    g = s.call("get_design", {"designId": d})
    mount = g["flightConfigurations"][0]["motors"][0]["mountId"]
    curve = [[0, 0], [0.2, 900], [3.5, 820], [3.8, 0]]
    m = s.call("create_custom_motor", {"designation": "Bench-Liquid", "manufacturer": "UTAT", "type": "liquid",
                                       "diameter": "38 mm", "length": "600 mm", "totalMass": "2.4 kg",
                                       "propellantMass": "1.0 kg", "thrustCurve": curve,
                                       "propellantCgFromTop": "200 mm", "dryCgFromTop": "400 mm",
                                       "saveTo": os.path.join(tmp, "Bench-Liquid.rse")})
    check(sc, ".rse file written for the OpenRocket GUI", os.path.exists(m["file"]))
    s.call("set_motor", {"designId": d, "mount": mount, "motor": "Bench-Liquid", "manufacturer": "UTAT"})
    sim = s.call("run_simulation", {"designId": d})
    check(sc, "simulates with the custom engine", num(sim["flight"]["apogee"]) > 100, sim["flight"]["apogee"])


def scenario_liquid_program(s):
    """'Is our N2O tank OK at 800 psi, and which probation level are we in?' (LC 2027 edicts)"""
    sc = "liquid program (2027 edicts)"
    pv = s.call("pressure_vessel", {"meop": "800 psi", "outerDiameter": "6 in", "wallThickness": "0.125 in",
                                    "ultimateStrength": "290 MPa", "welded": True, "weldKnockdown": 1.3,
                                    "proofPressure": "1200 psi"})
    burst_psi = 2 * 290e6 / 6894.757 * 0.125 / 6
    check(sc, "Barlow burst and 2 x MEOP x knockdown check", pv["burstCheck"] == ("PASS" if burst_psi >= 2 * 800 * 1.3 else "FAIL"),
          f'{pv["burstPressure"]} vs {pv["requiredBurst"]}')
    check(sc, "proof pressure >= 1.5 x MEOP", pv["proofCheck"] == "PASS", pv.get("proofCheck"))
    copv = s.call("pressure_vessel", {"meop": "750 psi", "burstPressure": "2800 psi", "copv": True})
    check(sc, "COPV needs 4 x MEOP (2800 < 3000 psi fails)", copv["burstCheck"] == "FAIL", copv["requiredBurst"])
    pl = s.call("advanced_probation", {"glppVolume": "6 L", "totalImpulse": "9000 Ns", "propellantMass": "7 kg",
                                       "targetAltitude": "10000 ft", "actualAltitude": "7000 ft"})
    check(sc, "6 L GLPP is probation level 1", pl["probationLevel"].startswith("PL1"), pl["probationLevel"])
    check(sc, "AASI = min(1, 7000/10000) x Isp", abs(num(pl["aasi"]) - 0.7 * 9000 / (7 * 9.80665)) < 0.2, pl["aasi"])


def scenario_dispersion_report(s, tmp):
    """'Where will it land in 15 km/h wind, and give me the design-review package.'"""
    sc = "dispersion + report"
    d = s.call("open_design", {"example": "Dual parachute"})["designId"]
    t0 = time.time()
    mc = s.call("monte_carlo", {"designId": d, "runs": 100, "windSpeed": "15 km/h"})
    dt = time.time() - t0
    check(sc, "100-run Monte Carlo < 20 s", dt < 20, f"{dt:.1f} s")
    check(sc, "landing ellipse reported", "ellipse2Sigma" in json.dumps(mc["landing"]))
    out = os.path.join(tmp, "review")
    s.call("generate_report", {"designId": d, "outputDir": out})
    check(sc, "report + two stability plots + CSV written",
          all(os.path.exists(os.path.join(out, f)) for f in
              ["report.md", "stability-ascent.svg", "stability-to-rail-exit.svg", "flight-data.csv"]))


def scenario_structures(s, d):
    """'Will our 1/8 in G10 fins flutter? How much nose weight for +0.5 cal? What drives our dispersion?'"""
    sc = "structures + vehicle dispersion"
    fl = s.call("fin_flutter", {"designId": d})
    fin = fl["finSets"][0]
    margin = num(fin["minMargin"])
    check(sc, "flutter margin reported with the worst point", margin > 0 and "altitude" in fin["worstPoint"], json.dumps(fin)[:300])
    check(sc, "shear modulus from the fin material (G10/fiberglass)", "fiberglass" in fin["shearModulus"], fin["shearModulus"])
    t_mm = float(fin["geometry"].split("thickness ")[1].split(" mm")[0])
    thin = s.call("fin_flutter", {"designId": d, "thickness": f"{t_mm / 2} mm"})["finSets"][0]
    ratio = num(thin["minMargin"]) / margin
    check(sc, "half the thickness -> ~0.35x margin (Vf ~ t^1.5)", 0.3 < ratio < 0.4, f"{ratio:.3f}")
    check(sc, "a failing fin gets a thickness / modulus fix", thin["status"] == "PASS" or "toReachRequiredMargin" in thin,
          thin["status"])
    req = s.call("check_requirements", {"designId": d, "includeWindCase": False})
    items = [c for c in req["checks"] if c["item"].startswith("Fin flutter margin")]
    check(sc, "check_requirements includes the flutter margin", len(items) == 1 and fin["minMargin"].split()[0] in items[0]["value"],
          json.dumps(items)[:300])

    now = json.dumps(s.call("run_simulation", {"designId": d}))
    cur = float(now.split('"minStabilityDuringAscent": "')[1].split()[0])
    target = round(cur + 0.5, 2)
    t0 = time.time()
    bal = s.call("ballast", {"designId": d, "targetStability": target})
    dt = time.time() - t0
    got = num(bal["effect"]["minAscentStability"].split("->")[1])
    check(sc, "ballast solve reaches the simulated target (+-0.05 cal)", abs(got - target) <= 0.05, f"{got} vs {target}")
    check(sc, "ballast reports the apogee cost", "->" in bal["effect"]["apogee"])
    check(sc, "ballast solve < 15 s", dt < 15, f"{dt:.1f} s")

    t0 = time.time()
    mc = s.call("monte_carlo", {"designId": d, "runs": 60, "thrustSd": 0.03, "massSd": 0.03, "dragSd": 0.1,
                                "chuteCdSd": 0.1})
    dt = time.time() - t0
    drv = mc.get("drivers", {}).get("apogee", {})
    check(sc, "vehicle-uncertainty Monte Carlo reports drivers", {"motorThrust", "airframeDrag", "structureMass"} <= set(drv),
          json.dumps(drv))
    check(sc, "drag lowers and thrust raises apogee", float(drv.get("airframeDrag", 0)) < 0 < float(drv.get("motorThrust", 0)),
          json.dumps(drv))
    check(sc, "60-run vehicle Monte Carlo < 20 s", dt < 20, f"{dt:.1f} s")


def scenario_openrocket_depth(s, d, tmp):
    """'Where does our drag come from? What do winds aloft do to our landing zone? Draw it for the review.'"""
    sc = "aero, winds aloft, parts, drawing"
    a = s.call("aero_analysis", {"designId": d, "maxMach": 1.5})
    rows = a["vsMach"]
    check(sc, "CD / CP / margin table vs Mach", len(rows) >= 8 and all("marginLaunch" in r for r in rows))
    cds = {float(r["mach"]): float(r["cd"]) for r in rows}
    check(sc, "transonic drag rise visible (CD at M1.0 > M0.5)", cds[1.0] > cds[0.5], json.dumps(cds))
    top = a["dragBreakdown"][0]
    check(sc, "per-component drag breakdown names the biggest contributor", float(top["share"].rstrip("%")) > 10, json.dumps(top))

    uniform = s.call("run_simulation", {"designId": d, "windSpeed": "20 km/h", "windDirection": "270 deg"})
    s.call("wind_profile", {"designId": d, "mode": "power_law", "groundSpeed": "20 km/h", "direction": "270 deg",
                            "exponent": 0.2, "top": "4000 m"})
    sheared = s.call("run_simulation", {"designId": d})
    far = lambda r: max(num(b["landingDistanceFromPad"]) for b in r["branches"] if "landingDistanceFromPad" in b)
    du, ds = far(uniform), far(sheared)
    check(sc, "winds aloft (shear profile) drift the vehicle farther than a uniform ground wind",
          du is not None and ds > du * 1.2, f"{du} vs {ds}")
    mc = s.call("monte_carlo", {"designId": d, "runs": 30})
    check(sc, "Monte Carlo runs on the wind profile", "landing" in mc and mc["failedRuns"] == 0)
    s.call("wind_profile", {"designId": d, "mode": "average"})

    parts = s.call("search_parts", {"type": "body_tube", "minOuterDiameter": "4.0 in", "maxOuterDiameter": "4.05 in",
                                    "text": "fiberglass"})
    check(sc, "parts database search by diameter and material", parts["matches"] > 0, json.dumps(parts)[:200])
    out = os.path.join(tmp, "draw.svg")
    s.call("draw_rocket", {"designId": d, "path": out})
    svg = open(out).read()
    check(sc, "rocket drawing with fins, CG and CP", svg.count("<polygon") >= 5 and "CG " in svg and "CP " in svg)


def main():
    if not os.path.exists(BIN):
        sys.exit(f"Build first: ./gradlew installDist ({BIN} missing)")
    tmp = tempfile.mkdtemp()
    t0 = time.time()
    s = Server()
    scratch = {}
    for name, fn in [("From-scratch 10k ft design", lambda: scratch.setdefault("d", scenario_from_scratch(s))),
                     ("Recovery chain", lambda: scenario_recovery(s)),
                     ("Two-stage", lambda: scenario_two_stage(s)),
                     ("Custom liquid engine", lambda: scenario_custom_engine(s, tmp)),
                     ("Liquid program (2027 edicts)", lambda: scenario_liquid_program(s)),
                     ("Dispersion + report", lambda: scenario_dispersion_report(s, tmp)),
                     ("Structures + vehicle dispersion", lambda: scenario_structures(s, scratch["d"])),
                     ("OpenRocket depth", lambda: scenario_openrocket_depth(s, scratch["d"], tmp))]:
        print(f"\n== {name}")
        try:
            fn()
        except Exception as e:  # a crashed scenario counts as a failure, keep going
            check(name, "scenario completed", False, repr(e)[:800])
    s.close()
    total = time.time() - t0
    passed = sum(1 for r in RESULTS if r[2])
    slow = sorted(s.calls, key=lambda c: -c[1])[:5]
    big = sorted(s.calls, key=lambda c: -c[3])[:5]
    chars = sum(c[3] for c in s.calls)
    print(f"\n{passed}/{len(RESULTS)} checks passed, {len(s.calls)} tool calls, {total:.1f} s total, "
          f"{chars / 1000:.0f}k chars of tool output (~{chars / 4000:.0f}k tokens)")
    print("slowest calls: " + ", ".join(f"{t} {dt:.1f}s" for t, dt, _, _ in slow))
    print("largest outputs: " + ", ".join(f"{t} {n / 1000:.1f}k" for t, _, _, n in big))
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
