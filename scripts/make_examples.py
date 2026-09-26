#!/usr/bin/env python3
"""Builds docs/EXAMPLES.md and its plots from real runs of the server: one 10,000 ft rocket followed from a blank page to
launch day, with the prompt a team would type and what comes back. Re-run after changes so the page stays true:

    ./gradlew installDist && python3 scripts/make_examples.py
"""
import json
import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import benchmark  # noqa: E402  (reuses its stdio client)

ROOT = benchmark.ROOT
DOCS = os.path.join(ROOT, "docs")
IMG = os.path.join(DOCS, "examples")
md = []


def w(text=""):
    md.append(text)


def table(rows, cols, headers=None):
    headers = headers or cols
    w("| " + " | ".join(headers) + " |")
    w("|" + "---|" * len(cols))
    for r in rows:
        w("| " + " | ".join(str(r.get(c, "")).replace("|", "/") for c in cols) + " |")
    w()


def ask(n, prompt, tools):
    w(f"### {n}. \"{prompt}\"")
    w()
    w(f"<sub>Tools Claude uses: {', '.join('`' + t + '`' for t in tools)}</sub>")
    w()


def lead(s):
    """Leading value of a formatted quantity: '3048 m (10000 ft)' -> '3048 m'."""
    return str(s).split(" (")[0]


def ft(s):
    """The value in feet from a dual-unit string such as '3048 m (10000 ft)'."""
    m = re.search(r"\(([-\d.]+) ft\)", str(s))
    return f"{float(m.group(1)):,.0f} ft" if m else str(s)


def compass(mean_point):
    """'-191 m (-627 ft) east, -7.9 m (-26 ft) north' -> '627 ft west, 26 ft south' (feet, rounded)."""
    m = re.findall(r"\(([-\d.]+) ft\) (east|north)", mean_point)
    parts = []
    for v, axis in m:
        v = float(v)
        name = {"east": ("east", "west"), "north": ("north", "south")}[axis][v < 0]
        if abs(v) >= 50:
            parts.append(f"{abs(v):,.0f} ft {name}")
    return ", ".join(parts) or "right around"


def main():
    if os.path.exists(IMG):
        shutil.rmtree(IMG)
    os.makedirs(IMG)
    tmp = tempfile.mkdtemp()
    s = benchmark.Server()
    raw = []

    def call(tool, args=None, **kw):
        out = s.call(tool, args, **kw)
        raw.append({"tool": tool, "args": args, "out": out})
        return out

    w("# Examples: what you ask, what you get")
    w()
    w("One rocket, from a blank page to launch day. Each step shows what a team member types, what Claude answers, and the")
    w("numbers and plots behind the answer. **Everything below is real output from the server** (OpenRocket 24.12), generated")
    w("by [`scripts/make_examples.py`](../scripts/make_examples.py); the one-line answers are written from those numbers.")
    w("Units follow the team setting (here metric with imperial in brackets).")
    w()
    w("The rocket: *Maple 10K*, a 4 in fiberglass, dual-deploy, single-stage rocket for the 10,000 ft category of Launch")
    w("Canada 2027.")
    w()

    # 1. Build ---------------------------------------------------------------------------------------------------------
    d = call("open_design", {"newRocketName": "Maple 10K"})["designId"]
    stage = call("get_design", {"designId": d})["components"][0].split("[")[1][:8]

    def add(parent, typ, name, props):
        return call("add_component", {"designId": d, "parent": parent, "type": typ, "name": name, "properties": props})

    add(stage, "NoseCone", "Nose cone", {"shapeType": "OGIVE", "length": "18 in", "aftDiameter": "4.02 in", "thickness": "2.5 mm",
                                          "material": "Fiberglass", "aftShoulderLength": "4 in", "aftShoulderDiameter": "3.9 in"})
    add(stage, "BodyTube", "Upper airframe", {"length": "24 in", "outerDiameter": "4.02 in", "thickness": "2 mm",
                                              "material": "Fiberglass"})
    add(stage, "BodyTube", "Lower airframe", {"length": "40 in", "outerDiameter": "4.02 in", "thickness": "2 mm",
                                              "material": "Fiberglass"})
    add("Lower airframe", "InnerTube", "Motor mount", {"length": "30 in", "outerDiameter": "78 mm", "thickness": "1.5 mm",
                                                        "axialMethod": "BOTTOM", "axialOffset": 0})
    add("Lower airframe", "TrapezoidFinSet", "Fins", {"finCount": 4, "rootChord": "9 in", "tipChord": "3 in", "span": "5 in",
                                                      "sweepLength": "5 in", "thickness": "3.2 mm", "material": "Fiberglass",
                                                      "axialMethod": "BOTTOM", "axialOffset": 0})
    add("Upper airframe", "Parachute", "Main", {"diameter": "60 in", "cd": 0.8})
    add("Lower airframe", "Parachute", "Drogue", {"diameter": "18 in", "cd": 0.8})
    add("Upper airframe", "MassComponent", "Avionics", {"mass": "1.2 kg", "length": "8 in"})
    call("set_deployment", {"designId": d, "component": "Drogue", "event": "apogee", "configuration": "all"})
    call("set_deployment", {"designId": d, "component": "Main", "event": "altitude", "altitude": "1000 ft", "configuration": "all"})

    w("## Design")
    w()
    ask(1, "Design a 4 inch fiberglass rocket with a 75 mm motor mount, dual deploy: 18 in drogue at apogee, 60 in main at "
           "1000 ft. 18 in ogive nose, 64 in of airframe, four trapezoidal fins.", ["open_design", "add_component", "set_deployment"])
    w("Claude builds the rocket part by part in OpenRocket (it opens in the OpenRocket app too) and saves it when asked.")
    w()

    # 2. Motor ---------------------------------------------------------------------------------------------------------
    def best_motor():
        r = call("rank_motors", {"designId": d, "mount": "Motor mount", "objective": "target_apogee",
                                 "targetApogee": "10000 ft", "minClass": "K"})
        call("set_motor", {"designId": d, "mount": "Motor mount", "motor": r["ranking"][0]["motor"]})
        return r

    rank = best_motor()
    rows = rank["ranking"][:6]
    top = rows[0]
    ask(2, "Which motor gets us closest to 10,000 ft?", ["rank_motors", "set_motor"])
    w(f"> **{top['motor']}**, {ft(top['apogee'].split(' (+')[0].split(' (-')[0])} predicted. {rank['candidates']}: each "
      "one was flown in the simulation, not just looked up, and motors that break a rule (rail exit speed, thrust-to-weight, "
      "stability) are ranked last.")
    w()
    table(rows, ["motor", "impulse", "apogee", "railExit", "maxMach", "minAscentStability", "meetsRules"],
          ["Motor", "Impulse", "Apogee (vs 10,000 ft)", "Rail exit", "Max Mach", "Min stability", "Meets rules"])
    call("save_design", {"designId": d, "path": os.path.join(tmp, "maple-pdr.ork")})  # the "PDR" version, compared later

    # 3. Flutter -------------------------------------------------------------------------------------------------------
    fl = call("fin_flutter", {"designId": d})["finSets"][0]
    ask(3, "It goes supersonic. Will our 1/8 in fins flutter?", ["fin_flutter"])
    wp = fl["worstPoint"]
    need = fl.get("toReachRequiredMargin", {})
    w(f"> **{fl['status'].split(':')[0]}.** At {wp['airspeed']} and {lead(wp['altitude'])} the estimated flutter speed is only "
      f"{lead(wp['flutterSpeed'])}: margin {fl['minMargin'].split(' ')[0]} against the team's required 1.5." +
      (f" Fins at least **{need['thickness'].split(' at ')[0]}** thick, a stiffer material or a lower aspect ratio fix it."
       if need else ""))
    w()

    # 4. Carbon fins, recorded in the team standards ----------------------------------------------------------------
    call("update_standards", {"patch": {"structures": {"shearModulus": {"carbon": {
        "value": "16 GPa", "source": "team coupon test, quasi-isotropic carbon/epoxy"}}}}})
    call("edit_components", {"designId": d, "changes": [{"component": "Fins", "properties": {
        "material": "Carbon fiber", "thickness": "0.25 in"}}]})
    fl = call("fin_flutter", {"designId": d})["finSets"][0]
    ask(4, "We'll make the fins from 1/4 in quasi-isotropic carbon. Our coupon test gave a shear modulus of 16 GPa: save that "
           "to our standards and check again.", ["update_standards", "edit_components", "fin_flutter"])
    w(f"> **{fl['status'].split(':')[0]}**, margin {fl['minMargin'].split(' ')[0]} (shear modulus {lead(fl['shearModulus'])} "
      "from your standards file, so every later check and the whole team use the measured value).")
    w()

    # 5. Fin size: lightest fins meeting stability and flutter, then the motor again ---------------------------------
    fins = [{"component": "Fins", "property": "height", "min": "3.5 in", "max": "8 in"},
            {"component": "Fins", "property": "rootChord", "min": "7 in", "max": "12 in"}]
    opt = call("optimize", {"designId": d, "objective": "min_mass", "meetRules": True, "variables": fins,
                            "maxEvaluations": 48, "apply": True})
    rank = best_motor()
    top = rank["ranking"][0]
    fl = call("fin_flutter", {"designId": d})["finSets"][0]
    ask(5, "Now make the fins as small and light as possible while meeting the stability rules (also in 30 km/h wind) and "
           "staying clear of flutter, then pick the motor again.", ["optimize", "rank_motors", "fin_flutter"])
    best = opt["best"]
    names = {"height": "span", "rootChord": "root chord"}
    w("> Fin " + ", ".join(f"{names.get(k.split('.')[1], k)} **{lead(v)}**" for k, v in best["values"].items()) +
      f" ({'meets every constraint' if opt['feasible'] else 'closest found'}): stability {best['minAscentStability']} to "
      f"{best['maxAscentStability']} in flight, flutter margin {best.get('finFlutterMargin', 'n/a')}. The lighter rocket "
      f"now flies best on the **{top['motor']}**: {ft(top['apogee'].split(' (+')[0].split(' (-')[0])}, Mach "
      f"{top['maxMach']}. Final flutter check: **{fl['status'].split(':')[0]}**, margin {fl['minMargin'].split(' ')[0]}.")
    w()
    w(f"<sub>Limits used: {opt['constraints'].get('fromRules', '')}; flutter margin ≥ "
      f"{opt['constraints'].get('minFinFlutterMargin', '1.5').split(' ')[0]} from the team standards.</sub>")
    w()

    draw = call("draw_rocket", {"designId": d, "path": os.path.join(IMG, "rocket.svg")})
    ask(6, "Show me the rocket.", ["draw_rocket"])
    w("![Maple 10K side view with CG and CP marked](examples/rocket.svg)")
    w()
    st = draw["stability"]
    w(f"{st['length'].split(' (')[1][:-1]} long, {st['launchMass']} on the pad, stability {st['stabilityAtLaunch']} at launch "
      f"and {st['stabilityAtBurnout']} at burnout.")
    w()

    # 6. Recovery ------------------------------------------------------------------------------------------------------
    w("## Recovery")
    w()
    size = call("size_parachute", {"designId": d, "device": "Main", "targetDescentRate": "20 ft/s"})
    ask(7, "What main do we need to land at 20 ft/s? And a drogue for about 85 ft/s.", ["size_parachute", "edit_components"])
    w(f"> The descending mass is {size['mass'].split(' (sim')[0]}, so the main needs a drag area of {size['requiredCdA']} "
      f"(a {lead(size['nominalDiameterAtCd0.8'])} flat canopy at Cd 0.8). Parachutes from OpenRocket's catalogue that do it:")
    w()
    listed = [m for m in size["presetMatches"] if not m["cd"].startswith("not listed")]
    matches = (listed + [m for m in size["presetMatches"] if m not in listed])[:4]
    table(matches, ["parachute", "diameter", "cd", "descentRate", "packed", "mass"],
          ["Parachute", "Diameter", "Cd", "Descent", "Packed size", "Mass"])
    pick = matches[0]
    dsize = call("size_parachute", {"designId": d, "device": "Drogue", "targetDescentRate": "85 ft/s"})
    m = re.search(r"\(([\d.]+) in\)", dsize["nominalDiameterAtCd0.8"])
    drogue_in = int(float(m.group(1)) + 0.999)
    cd = float(re.search(r"[\d.]+", pick["cd"]).group(0))  # "not listed (0.8 assumed)" -> 0.8
    main_in = re.search(r"\(([\d.]+) in\)", pick["diameter"]).group(1)
    call("edit_components", {"designId": d, "changes": [
        {"component": "Main", "properties": {"diameter": f"{main_in} in", "cd": cd}},
        {"component": "Drogue", "properties": {"diameter": f"{drogue_in} in", "cd": 0.8}}]})
    w(f"*\"Use the {pick['parachute']} and a {drogue_in} in drogue.\"*")
    w()

    rec = call("recovery_analysis", {"designId": d, "pinType": "4-40 nylon"})
    ask(8, "What loads do the chutes see when they open, and how many 4-40 nylon shear pins do we need?",
        ["recovery_analysis", "ejection_charge"])
    rows = [{"device": x["device"], "opens at": f"{lead(x['altitudeAGL'])}, {lead(x['airspeedAtDeployment'])}",
             "design load": x["designLoad"].split(" [")[0], "harness rating": lead(x["harnessWorkingLoad"]),
             "descent": x["steadyDescentRate"]} for x in rec["deployments"]]
    table(rows, list(rows[0].keys()))
    pins = [x["shearPinsForLaterBays"] for x in rec["deployments"] if "shearPinsForLaterBays" in x]
    bp = call("ejection_charge", {"bayDiameter": "4 in", "bayLength": "12 in", "pressure": "15 psi"})
    w(f"> Shear pins on the main bay: **{pins[0].split(' (')[0] if pins else 'n/a'}** (they must hold while the drogue opens). "
      f"Black powder for a 4 × 12 in bay at 15 psi: **{bp['primaryCharge']}** primary, {bp['backupCharge']} backup. The harness "
      "rating is twice the opening load, for shock cord, quick links and eye bolts.")
    w()

    # 8. Rules ---------------------------------------------------------------------------------------------------------
    w("## Flight and rules")
    w()
    req = call("check_requirements", {"designId": d})
    ask(9, "Does it pass Launch Canada?", ["check_requirements"])
    w(f"> **{req['summary']}** Every check cites its rule, and there is a checklist of "
      f"{len(req.get('manualChecks', []))} things to verify by hand (electronics, radio, structures, operations).")
    w()
    order = {"FAIL": 0, "WARN": 1, "PASS": 2, "INFO": 3}
    picks = sorted(req["checks"], key=lambda c: order.get(c["status"], 4))[:12]
    table(picks, ["status", "item", "value", "ref"], ["", "Check", "Value", "Rule"])

    sim = call("run_simulation", {"designId": d, "plotPath": os.path.join(IMG, "flight-profile.svg")})
    f = sim["flight"]
    ask(10, "Simulate the flight and plot it.", ["run_simulation"])
    w(f"> Apogee **{f['apogee']}** at {f['timeToApogee']}. Top speed {f['maxVelocity']} (Mach {f['maxMach']}), "
      f"{f['maxAcceleration'].split(' = ')[-1]} peak, {f['railExitVelocity']} off the rail.")
    w()
    w("![Altitude against time with burnout, apogee and both deployments marked](examples/flight-profile.svg)")
    w()

    mc = call("monte_carlo", {"designId": d, "runs": 200, "windSpeed": "15 km/h", "windDirection": "270 deg",
                              "massSd": 0.03, "dragSd": 0.1, "thrustSd": 0.03, "chuteCdSd": 0.1,
                              "plotPath": os.path.join(IMG, "landing.svg")})
    ask(11, "Where will it land in a 15 km/h west wind? Include our build and motor uncertainty.", ["monte_carlo"])
    land = next(iter(mc["landing"].values()))
    ap = mc["apogee"]
    w(f"> Over 200 simulated flights the median landing is {land['distanceFromPad']['median']} from the pad and 95% land "
      f"within {land['distanceFromPad']['p95']}; the landings centre {compass(land['meanPoint'])} of the pad (the rail is "
      f"tilted into the wind, so it flies upwind and drifts back under the drogue). Apogee {ap['mean']} ± {ap['sd']}.")
    w()
    w("![200 simulated landings around the pad with the 2-sigma ellipse](examples/landing.svg)")
    w()
    drv = mc.get("drivers", {})
    if drv:
        w("Claude also reports what drives the spread (correlation of each uncertain input with the result), so the team "
          "knows what to measure more carefully:")
        w()
        results = list(drv.keys())
        inputs = list(next(iter(drv.values())).keys())
        label = {"apogee": "Apogee", "minAscentStability": "Min stability", "farthestLanding": "Landing distance"}
        rows = [dict({"input": i}, **{r: drv[r].get(i, "") for r in results}) for i in inputs]
        table(rows, ["input"] + results, ["Uncertain input"] + [label.get(r, r) for r in results])
        w("<sub>Correlation from -1 to 1: the closer to ±1, the more that input drives the result.</sub>")
        w()

    # 11. Shapes -------------------------------------------------------------------------------------------------------
    w("## Design studies")
    w()
    sh = call("compare_shapes", {"designId": d})
    ask(12, "Would a different nose cone or fin shape fly higher?", ["compare_shapes"])
    w(f"> Best that keeps the stability: **{sh.get('bestMeetingStability', '')}**. Every nose profile and fin edge was "
      f"flown; CD at the design Mach ({sh['designMach'].split(' ')[0]}) shows where the gain comes from.")
    w()
    opts = sh["options"][:8]
    table(opts, ["nose", "finEdges", "apogee", "vsCurrent", "cdAtDesignMach", "minAscentStability"],
          ["Nose", "Fin edges", "Apogee", "vs current", "CD", "Min stability"])
    guidance = sh.get("guidance")
    if guidance:
        w("<details><summary>Claude's shape guidance (from the tool)</summary>")
        w()
        for g_ in (guidance if isinstance(guidance, list) else [guidance]):
            w(f"- {g_}")
        w()
        w("</details>")
        w()

    # 12. Review -------------------------------------------------------------------------------------------------------
    w("## Reviews")
    w()
    rep_dir = os.path.join(tmp, "report")
    call("generate_report", {"designId": d, "outputDir": rep_dir, "title": "Maple 10K design review"})
    shutil.copy(os.path.join(rep_dir, "stability-ascent.svg"), os.path.join(IMG, "stability-ascent.svg"))
    ask(13, "Make the design review package.", ["generate_report"])
    w("Claude writes a folder with `report.md` (requirement checks, vehicle, flight, stability, recovery, wind table, "
      "methods), the stability-vs-time plots Launch Canada asks for (DTEG R10.3.2), the drawing, the flight profile and a CSV "
      "of the flight data.")
    w()
    w("![Stability margin during the ascent with the rule minimum](examples/stability-ascent.svg)")
    w()

    diff = call("compare_designs", {"designId": d, "baselinePath": os.path.join(tmp, "maple-pdr.ork")})
    ask(14, "What changed since the version we showed at PDR?", ["compare_designs"])
    for line in diff["summary"]:
        w(f"- {line}")
    w()
    for c in diff["componentChanges"][:4]:
        if c.get("edits"):
            w(f"- **{c['component']}**: " + "; ".join(c["edits"]))
    w()

    # 14. Launch day ---------------------------------------------------------------------------------------------------
    w("## Launch day")
    w()
    sample = open(os.path.join(ROOT, "src", "test", "resources", "open-meteo-sample.json")).read()
    ask(15, "We launch at 48.47, -81.33 on August 21 at 3 pm. What will the winds do, and make us the flight card.",
        ["weather_forecast", "flight_card"])
    wx = call("weather_forecast", {"designId": d, "forecastJson": sample, "time": "2027-08-21T15:00"})
    g = wx["ground"]
    w(f"> Ground wind {g.get('wind')}, gusts {g.get('gusts')} ({g.get('vsRuleLimit', '')}). Flown in the forecast winds "
      f"aloft: apogee {wx.get('flightInForecast', {}).get('apogee', '')}, landing "
      f"{', '.join(wx.get('flightInForecast', {}).get('landingDistance', [])).replace('Sustainer ', '')} from the pad.")
    w()
    w("<sub>(A recorded forecast is used here so the page is reproducible; with internet access Claude fetches the live one "
      "from Open-Meteo.)</sub>")
    w()
    aloft = wx.get("windsAloft", [])
    if aloft:
        w("Winds aloft from the forecast, flown as a wind profile:")
        w()
        for line in aloft[1::3][:6]:
            w(f"- {line}")
        w()
    card = os.path.join(tmp, "flight-card.md")
    call("flight_card", {"designId": d, "path": card})
    text = open(card).read()
    excerpt = text.split("## Predicted flight")[1].split("## Recovery")[0].strip() if "## Predicted flight" in text else ""
    w("From the one-page flight card (predictions, motor delay, deployment settings, drift in each wind, rule check and "
      "sign-off lines):")
    w()
    w("> **Predicted flight**")
    w(">")
    for line in excerpt.splitlines():
        if not line.startswith("| Wind |"):
            w("> " + line)
    w()

    w("## After the flight")
    w()
    w("*\"Here is our altimeter file — how did we do compared with the prediction?\"* Claude lines the log up with the "
      "simulation (`compare_flight`), fits the drag so the next prediction is closer, and plots simulated against measured "
      "altitude.")
    w()
    s.close()
    if os.environ.get("EXAMPLES_DUMP"):
        json.dump(raw, open(os.environ["EXAMPLES_DUMP"], "w"), indent=1)
    with open(os.path.join(DOCS, "EXAMPLES.md"), "w") as fh:
        fh.write("\n".join(md).rstrip() + "\n")
    print(f"wrote docs/EXAMPLES.md and {len(os.listdir(IMG))} plots in docs/examples/")


if __name__ == "__main__":
    main()
