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
    w("Units follow the team setting (here metric with imperial in brackets). In Claude Desktop the plots and drawings")
    w("appear right in the chat, and long runs (optimizers, Monte Carlo) show their progress and can be stopped.")
    w()
    w("**Steps:** [Design](#design) · [Recovery](#recovery) · [Flight and rules](#flight-and-rules) ·")
    w("[Design studies](#design-studies) · [Structures and CFD](#structures-and-cfd) · [Build](#build) · [Reviews](#reviews) ·")
    w("[Launch day](#launch-day) · [Show it off](#show-it-off) · [After the flight](#after-the-flight)")
    w()
    w("The rocket: *Maple 10K*, a 4 in fiberglass, dual-deploy, single-stage rocket for the 10,000 ft category of Launch")
    w("Canada 2027. Try it yourself: open [`examples/maple-10k-pdr.ork`](examples/maple-10k-pdr.ork) (the early version, before")
    w("step 3) or [`examples/maple-10k.ork`](examples/maple-10k.ork) (the finished design) and ask the same questions.")
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
    add("Nose cone", "Bulkhead", "Nose bulkhead", {"length": "0.25 in", "axialMethod": "BOTTOM", "axialOffset": "4 in"})
    add("Lower airframe", "InnerTube", "Motor mount", {"length": "30 in", "outerDiameter": "78 mm", "thickness": "1.5 mm",
                                                        "axialMethod": "BOTTOM", "axialOffset": 0})
    for i, off in enumerate(["0 in", "-12 in", "-29.75 in"]):
        add("Lower airframe", "CenteringRing", f"Centering ring {i + 1}", {"length": "0.25 in", "axialMethod": "BOTTOM",
                                                                          "axialOffset": off})
    add("Lower airframe", "TrapezoidFinSet", "Fins", {"finCount": 4, "rootChord": "9 in", "tipChord": "3 in", "span": "5 in",
                                                      "sweepLength": "5 in", "thickness": "3.2 mm", "material": "Fiberglass",
                                                      "axialMethod": "BOTTOM", "axialOffset": 0})
    add("Lower airframe", "RailButton", "Rail buttons", {"instanceCount": 2, "instanceSeparation": "24 in",
                                                         "axialMethod": "BOTTOM", "axialOffset": "-26 in"})
    add("Upper airframe", "Parachute", "Main", {"diameter": "60 in", "cd": 0.8})
    add("Upper airframe", "ShockCord", "Main shock cord", {"cordLength": "25 ft", "length": "4 in"})
    add("Lower airframe", "Parachute", "Drogue", {"diameter": "18 in", "cd": 0.8})
    add("Lower airframe", "ShockCord", "Drogue shock cord", {"cordLength": "20 ft", "length": "3 in"})
    call("set_deployment", {"designId": d, "component": "Drogue", "event": "apogee", "configuration": "all"})
    call("set_deployment", {"designId": d, "component": "Main", "event": "altitude", "altitude": "1000 ft", "configuration": "all"})

    w("## Design")
    w()
    ask(1, "Design a 4 inch fiberglass rocket with a 75 mm motor mount, dual deploy: 18 in drogue at apogee, 60 in main at "
           "1000 ft. 18 in ogive nose, 64 in of airframe, four trapezoidal fins.", ["open_design", "add_component", "set_deployment"])
    w("Claude builds the rocket part by part in OpenRocket, inside as well as out: nose bulkhead, motor mount with three "
      "centering rings, rail buttons, both parachutes (with realistic packed sizes) and their shock cords. It opens in the "
      "OpenRocket app too.")
    w()

    bay = call("add_avionics_bay", {"designId": d})
    ask(2, "Lay out the avionics bay between the two airframes: two independent altimeters, a GPS tracker, and the "
           "ejection charges.", ["add_avionics_bay"])
    w(f"> {bay['added']}: {bay['electronics']}; {bay['charges']}. Bay mass {bay['bayMass']}. Static ports: "
      f"{bay['staticPorts'].split(';')[0]}.")
    w()
    rows = [{"part": x["part"], "position": lead(x["fromNoseTip"]) + " from the nose tip", "mass": lead(x["mass"])}
            for x in bay["layout"]]
    w("<details><summary>Bay layout</summary>")
    w()
    table(rows, ["part", "position", "mass"], ["Part", "Position", "Mass"])
    w("</details>")
    w()
    short = [x for x in bay.get("recoveryPacked", []) if x.startswith("WARNING")]
    base = {"Upper airframe": 24, "Lower airframe": 40}
    fixes = []
    for msg in short:
        tube = re.search(r"room in (.+?): ", msg).group(1)
        need = float(re.search(r"need [^(]*\(([\d.]+) in\)", msg).group(1))
        have = float(re.search(r"only [^(]*\(([\d.]+) in\)", msg).group(1))
        new_len = base[tube] + int(need - have + 0.999) + 1  # an inch spare (e.g. a motor slightly longer than its mount)
        w(f"> ⚠️ {msg[len('WARNING: '):]}")
        w()
        call("edit_components", {"designId": d, "changes": [{"component": tube, "properties": {"length": f"{new_len} in"}}]})
        fixes.append(f"the {tube.lower()} to {new_len} in")
    if fixes:
        w(f"*\"Lengthen {' and '.join(fixes)}.\"* The bay packs the parachutes against itself and the motor mount, fins and "
          "rail buttons are positioned from the aft end, so everything keeps its place and the parachutes get the room.")
        w()
    w("The layout follows the Launch Canada electronics edicts: one altimeter per circuit, each with its own battery and "
      "physical switch; the tracker on its own battery; charges on the bulkhead facing the bay they open. Masses are "
      "typical defaults: give Claude your actual altimeters, batteries and tracker (name, mass, length) and it uses them.")
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
    ask(3, "Which motor gets us closest to 10,000 ft?", ["rank_motors", "set_motor"])
    w(f"> **{top['motor']}**, {ft(top['apogee'].split(' (+')[0].split(' (-')[0])} predicted. {rank['candidates']}: each "
      "one was flown in the simulation, not just looked up, and motors that break a rule (rail exit speed, thrust-to-weight, "
      "stability) are ranked last.")
    w()
    table(rows, ["motor", "impulse", "apogee", "railExit", "maxMach", "minAscentStability", "meetsRules"],
          ["Motor", "Impulse", "Apogee (vs 10,000 ft)", "Rail exit", "Max Mach", "Min stability", "Meets rules"])
    # The "PDR" version, compared later; also shipped for people to try (and for the demo video).
    call("save_design", {"designId": d, "path": os.path.join(tmp, "maple-pdr.ork")})
    shutil.copy(os.path.join(tmp, "maple-pdr.ork"), os.path.join(IMG, "maple-10k-pdr.ork"))

    # 3. Flutter -------------------------------------------------------------------------------------------------------
    fl = call("fin_flutter", {"designId": d})["finSets"][0]
    ask(4, "It goes supersonic. Will our 1/8 in fins flutter?", ["fin_flutter"])
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
    ask(5, "We'll make the fins from 1/4 in quasi-isotropic carbon. Our coupon test gave a shear modulus of 16 GPa: save that "
           "to our standards and check again.", ["update_standards", "edit_components", "fin_flutter"])
    w(f"> **{fl['status'].split(':')[0]}**, margin {fl['minMargin'].split(' ')[0]} (shear modulus {lead(fl['shearModulus'])} "
      "from your standards file, so every later check and the whole team use the measured value).")
    w()

    # 5. Stability and fin size: nose ballast to the rule floor, then the lightest fins that meet stability and flutter,
    #    then the motor again (up to two rounds: the motor changes the speed, which moves both).
    ballast_total = 0.0
    for rnd in range(2):
        bal = call("ballast", {"designId": d})
        grams = float(lead(bal["ballastMass"]).split()[0]) * (1000 if " kg" in lead(bal["ballastMass"]) else 1)
        if grams > 1:
            ballast_total += grams
            pos = bal["location"].split(" + ")[1].split(" = ")[0]
            existing = [c for c in call("get_design", {"designId": d})["components"] if "Nose ballast" in c]
            if existing:
                call("edit_components", {"designId": d, "changes": [{"component": "Nose ballast",
                                                                     "properties": {"componentMass": f"{ballast_total:.0f} g"}}]})
            else:
                add("Nose cone", "MassComponent", "Nose ballast", {"componentMass": f"{grams:.0f} g", "length": "2 in",
                                                                   "axialMethod": "TOP", "axialOffset": pos})
        opt = call("optimize_fins", {"designId": d, "objective": "max_apogee", "maxSpan": "8 in", "apply": True,
                                     "plotPath": os.path.join(IMG, "fins.svg")})
        rank = best_motor()
        top = rank["ranking"][0]
        fl = call("fin_flutter", {"designId": d})["finSets"][0]
        if opt["feasible"] and fl["status"].startswith("PASS"):
            break
    ask(6, "It has to meet the stability rules (also in 30 km/h wind): add the nose weight it needs, then optimize the fin "
           "shape for the least drag while staying clear of flutter (span no more than 8 in), and pick the motor again.",
        ["ballast", "add_component", "optimize_fins", "rank_motors", "fin_flutter"])
    best = opt["optimized"]
    w(f"> **{ballast_total:.0f} g of nose ballast**, then the whole fin shape at once: root chord **{lead(best['rootChord'])}**, "
      f"tip chord **{lead(best['tipChord'])}**, span **{lead(best['span'])}**, sweep **{lead(best['sweep'].split(' (lead')[0])}** "
      f"({'meets every constraint' if opt['feasible'] else 'closest found'}; launch mass {opt['change']['launchMass']}): "
      f"stability {best['minAscentStability']} to {best['maxAscentStability']} in flight, flutter margin "
      f"{best.get('finFlutterMargin', 'n/a')}. Best motor now: "
      f"**{top['motor']}**, {ft(top['apogee'].split(' (+')[0].split(' (-')[0])}, Mach {top['maxMach']}. Final flutter check: "
      f"**{fl['status'].split(':')[0]}**, margin {fl['minMargin'].split(' ')[0]}.")
    w()
    w("![Current and optimized fin planforms, root on the body line](examples/fins.svg)")
    w()
    w("<sub>Only buildable fins are tried: tip chord at least 0.5 in, no tip trailing edge behind the root (it would take the "
      "landing), leading-edge sweep at most 65 deg, span at most 8 in as asked. Why ballast: with the real avionics bay "
      "modelled, the electronics sit further aft than a single lump would, so the CG moves back. Limits used: "
      f"{opt['constraints'].get('fromRules', '')}; flutter margin ≥ "
      f"{opt['constraints'].get('minFinFlutterMargin', '1.5').split(' ')[0]} from the team standards.</sub>")
    w()

    draw = call("draw_rocket", {"designId": d, "path": os.path.join(IMG, "rocket.svg")})
    ask(7, "Show me the rocket.", ["draw_rocket"])
    w("![Maple 10K cut-away: nose with GPS tracker, main and shock cord, the avionics bay with both altimeter circuits and "
      "charges, drogue, motor mount and motor, with the separation points, CG and CP marked](examples/rocket.svg)")
    w()
    w("A cut-away from the OpenRocket model itself: every part is drawn where it is, electronics coloured by role, and the "
      "red dashes show where the rocket separates (main at the nose, drogue below the avionics bay).")
    w()
    st = draw["stability"]
    w(f"{st['length'].split(' (')[1][:-1]} long, {st['launchMass']} on the pad, stability {st['stabilityAtLaunch']} at launch "
      f"and {st['stabilityAtBurnout']} at burnout.")
    w()

    # What-if and undo --------------------------------------------------------------------------------------------------
    call("edit_components", {"designId": d, "changes": [{"component": "Fins", "properties": {"thickness": "1/8 in"}}]})
    thin = call("fin_flutter", {"designId": d})["finSets"][0]
    ask(8, "What if we used 1/8 in carbon for the fins to save weight?", ["edit_components", "fin_flutter"])
    w(f"> Claude makes the change and checks it: flutter **{thin['status'].split(':')[0]}**, margin "
      f"{thin['minMargin'].split(' ')[0]} (flutter speed / airspeed; the team wants {thin['requiredMargin'].split(' ')[0]}). "
      f"Thinner fins would need {lead(thin['toReachRequiredMargin']['thickness'])} to be safe."
      if 'toReachRequiredMargin' in thin else f"> Flutter **{thin['status'].split(':')[0]}**, margin {thin['minMargin'].split(' ')[0]}.")
    w()
    hist = call("history", {"designId": d})
    undo = call("undo", {"designId": d})
    ask(9, "Undo that.", ["undo", "history"])
    w(f"> Undone: *{undo['undone'][0].split(' (')[0]}* (the thickness change). Every edit in the session can be undone or "
      f"redone, newest first; `history` lists them ({len(hist['canUndo'])} so far in this session). Nothing touches the "
      "`.ork` file until you ask Claude to save.")
    w()

    # 6. Recovery ------------------------------------------------------------------------------------------------------
    w("## Recovery")
    w()
    size = call("size_parachute", {"designId": d, "device": "Main", "targetDescentRate": "20 ft/s"})
    ask(10, "What main do we need to land at 20 ft/s? And a drogue for about 85 ft/s.", ["size_parachute", "edit_components"])
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
    ask(11, "What loads do the chutes see when they open, and how many 4-40 nylon shear pins do we need?",
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
    ask(12, "Does it pass Launch Canada?", ["check_requirements"])
    w(f"> **{req['summary']}** Every check cites its rule, and there is a checklist of "
      f"{len(req.get('manualChecks', []))} things to verify by hand (electronics, radio, structures, operations).")
    w()
    order = {"FAIL": 0, "WARN": 1, "PASS": 2, "INFO": 3}
    picks = sorted(req["checks"], key=lambda c: order.get(c["status"], 4))[:12]
    table(picks, ["status", "item", "value", "ref"], ["", "Check", "Value", "Rule"])

    sim = call("run_simulation", {"designId": d, "plotPath": os.path.join(IMG, "flight-profile.svg")})
    f = sim["flight"]
    ask(13, "Simulate the flight and plot it.", ["run_simulation"])
    w(f"> Apogee **{f['apogee']}** at {f['timeToApogee']}. Top speed {f['maxVelocity']} (Mach {f['maxMach']}), "
      f"{f['maxAcceleration'].split(' = ')[-1]} peak, {f['railExitVelocity']} off the rail.")
    w()
    w("![Altitude against time with burnout, apogee and both deployments marked](examples/flight-profile.svg)")
    w()

    mc = call("monte_carlo", {"designId": d, "runs": 200, "windSpeed": "15 km/h", "windDirection": "270 deg",
                              "massSd": 0.03, "dragSd": 0.1, "thrustSd": 0.03, "chuteCdSd": 0.1,
                              "plotPath": os.path.join(IMG, "landing.svg"), "kmlPath": os.path.join(IMG, "landing.kml"),
                              "siteLatitude": 48.47, "siteLongitude": -81.33})
    ask(14, "Where will it land in a 15 km/h west wind? Include our build and motor uncertainty, and give us a map for "
            "Google Earth.", ["monte_carlo"])
    land = next(iter(mc["landing"].values()))
    ap = mc["apogee"]
    w(f"> Over 200 simulated flights the median landing is {land['distanceFromPad']['median']} from the pad and 95% land "
      f"within {land['distanceFromPad']['p95']}; the landings centre {compass(land['meanPoint'])} of the pad (the rail is "
      f"tilted into the wind, so it flies upwind and drifts back under the drogue). Apogee {ap['mean']} ± {ap['sd']}.")
    w()
    w("![200 simulated landings around the pad with the 2-sigma ellipse](examples/landing.svg)")
    w()
    w("[`landing.kml`](examples/landing.kml) puts the pad, every landing and the ellipse on the map in Google Earth (desktop, "
      "web or phone), so the recovery team and the RSO see the fields, roads and trees it covers.")
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
    ask(15, "Would a different nose cone or fin shape fly higher?", ["compare_shapes"])
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

    # 12. FEA and CFD hand-offs -----------------------------------------------------------------------------------------
    w("## Structures and CFD")
    w()
    fea = call("fin_fea", {"designId": d, "outDir": os.path.join(tmp, "fea"), "youngsModulus": "45 GPa",
                           "poissonRatio": 0.3, "plotPath": os.path.join(IMG, "fin-stress.svg")})
    loads = call("structural_loads", {"designId": d, "csvPath": os.path.join(tmp, "joint-loads.csv")})
    ask(16, "Check the fins in FEA: our quasi-isotropic laminate has E = 45 GPa and Poisson's ratio 0.3. And give us the "
            "joint loads for the airframe FEA.", ["fin_fea", "structural_loads"])
    fe, ld = fea["fea"], fea["load"]
    if isinstance(fe, dict):
        w(f"> **{fe['status']}**, margin of safety {fe['marginOfSafety'].split(' ')[0]} with the team's safety factor of "
          f"{ld['safetyFactor']}. Design load {ld['normalForceOnFin']}, from the {ld['basis']}. Tip deflection "
          f"{lead(fe['maxDeflection'])}; peak stress {lead(fe['peakStress'])}. Natural frequencies: "
          + ", ".join(fe["naturalFrequencies"][:3]) + ".")
        w()
        w("![The fin coloured by stress under the design load, clamped at the root](examples/fin-stress.svg)")
        w()
        w("Claude writes a CalculiX model of the fin (8-node shells, clamped root, the flight load) and runs it when CalculiX is "
          "installed. The input deck is kept, so a team member can refine it in PrePoMax or Abaqus. The model was checked "
          "against cantilever plate theory: deflection, root stress and first frequency agree within 0.2%. The stress peaks at "
          "the root, most at its corners, where the real fin's fillet or tab spreads the load.")
        w()
        w(f"Joint loads for the airframe: {len(loads['joints'])} joints, the highest wall stress at "
          f"{loads.get('highestWallStress', 'n/a')}; the load cases (axial force, bending moments, the flight time, Mach and "
          "angle of attack of each) go to a CSV for the tube and coupler FEA.")
    else:
        w(f"> {fe}")
    w()

    geo = call("export_geometry", {"designId": d, "outDir": os.path.join(tmp, "cfd")})
    shutil.copy(os.path.join(tmp, "cfd", "preview.svg"), os.path.join(IMG, "cfd-model.svg"))
    ask(17, "Export it for CFD, and tell us which cases to run.", ["export_geometry"])
    w(f"> The rocket as STL ({geo['files']['stl'].split('(')[1].split(';')[0]}, one region per part so the solver reports "
      "the force on each), the fin cutting pattern as DXF for the waterjet or laser, and a run matrix taken from the simulated flight: each Mach "
      "number at the altitude where the rocket reaches it. When the CFD results are in, `import_aero_table` reads them "
      "back and every later simulation and rule check uses them.")
    w()
    w("![The exported CFD model: nose, airframe sections, base and fins as separate regions](examples/cfd-model.svg)")
    w()
    cases = [c for c in geo["cases"] if c["case"].startswith("M") or c["case"] == "max_q"]
    table(cases, ["case", "mach", "altitude", "velocity", "reynolds", "openrocketCd"],
          ["Case", "Mach", "Altitude", "Velocity", "Reynolds", "OpenRocket CD"])

    # Weigh-in ---------------------------------------------------------------------------------------------------------
    w("## Build")
    w()
    tpl = call("mass_budget", {"designId": d, "csvPath": os.path.join(tmp, "budget-template.csv")})
    import csv as _csv
    rows = list(_csv.DictReader(open(tpl["template"])))
    model = {r["part"]: float(r["mass (g)"]) for r in rows}
    # Sample weigh-in: the finished parts come out heavier than the model (resin, hardware), plus parts the model lacks.
    sheet = [("Nose cone", 1.12, "measured", "no", ""), ("Upper airframe", 1.08, "measured", "no", ""),
             ("Lower airframe", 1.10, "measured", "no", ""), ("Fins", 1.15, "measured", "no", ""),
             ("Av-bay coupler", 1.05, "measured", "no", "")]
    lines = ["part,mass (g),status,section,parent"]
    for part, k, st_, sec, par in sheet:
        if model.get(part, 0) >= 1:
            lines.append(f"{part},{model[part] * k:.0f},{st_},{sec},{par}")
    lines += ["Fin fillets and tip-to-tip epoxy,140,measured,no,Lower airframe", "Paint and primer,90,estimated,no,Upper airframe"]
    weigh = "\n".join(lines) + "\n"
    mb = call("mass_budget", {"designId": d, "csv": weigh, "targetLaunchMass": "12 kg", "contingency": 0.1})
    ask(18, "We weighed the parts we've built. Here is our weigh-in sheet; how does it compare with the model? Our launch mass "
           "target is 12 kg.", ["mass_budget"])
    w("<details><summary>Weigh-in sheet (sample numbers)</summary>")
    w()
    w("```csv")
    w(weigh.strip())
    w("```")
    w()
    w("</details>")
    w()
    table(mb["items"][:7], ["part", "budget", "model", "difference", "flag"], ["Part", "Weigh-in", "Model", "Difference", ""])
    t_ = mb["totals"]
    w(f"> Projected launch mass **{t_['projectedLaunch'].split(' (with')[0]}**, including {t_['motor']} of motor and 10% "
      f"contingency on everything not weighed yet: {t_.get('margin', '')} of 12 kg. {sum(1 for i in mb['items'] if i.get('flag') == 'CHECK')} "
      f"part(s) differ from the model by more than 10% (flagged). The sheet leaves out {t_['notInBudget'].split(' (')[0]} of parts the model has (heaviest: "
      f"{', '.join(mb.get('heaviestNotInBudget', [])[:3])}), which stay at the model's values.")
    w()
    ap_ = call("mass_budget", {"designId": d, "csv": weigh, "apply": True})
    ask(19, "Put the weigh-ins into the design.", ["mass_budget"])
    ch = ap_["change"]
    w(f"> Done: {len(ap_['applied'])} changes (mass overrides on the weighed parts, the fillets and paint added as mass "
      f"components). Launch mass {ch['launchMass']}, CG {ch['cgAtLaunch']}, stability {ch['stabilityAtLaunch']}. Every later "
      "step flies the rocket as built; `undo` takes it back in one step.")
    w()

    # 13. Review -------------------------------------------------------------------------------------------------------
    w("## Reviews")
    w()
    stat = call("design_status", {"designId": d})
    ask(20, "Where do we stand? What's left before CDR?", ["design_status"])
    w(f"> **{stat['readiness']}.** {stat.get('ruleCheck', '')}")
    w()
    rows = [{"area": i["area"], "status": i["status"], "finding": i["finding"]} for i in stat["items"]][:8]
    if rows:
        table(rows, ["area", "status", "finding"], ["Area", "Status", "Finding"])
    w("Next steps, in order:")
    w()
    for n_, step in enumerate(stat["nextSteps"], 1):
        w(f"{n_}. {step}")
    w()
    rep_dir = os.path.join(tmp, "report")
    call("generate_report", {"designId": d, "outputDir": rep_dir, "title": "Maple 10K design review"})
    shutil.copy(os.path.join(rep_dir, "stability-ascent.svg"), os.path.join(IMG, "stability-ascent.svg"))
    ask(21, "Make the design review package.", ["generate_report"])
    w("Claude writes a folder with `report.md` (requirement checks, vehicle, flight, stability, recovery, wind table, "
      "methods), the stability-vs-time plots Launch Canada asks for (DTEG R10.3.2), the drawing, the flight profile and a CSV "
      "of the flight data.")
    w()
    w("![Stability margin during the ascent with the rule minimum](examples/stability-ascent.svg)")
    w()

    call("save_design", {"designId": d, "path": os.path.join(IMG, "maple-10k.ork")})
    diff = call("compare_designs", {"designId": d, "baselinePath": os.path.join(tmp, "maple-pdr.ork")})
    ask(22, "What changed since the version we showed at PDR?", ["compare_designs"])
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
    ask(23, "We launch at 48.47, -81.33 on August 21 at 3 pm. What will the winds do, and make us the flight card.",
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

    # Show it off ------------------------------------------------------------------------------------------------------
    w("## Show it off")
    w()
    ex = call("render_3d", {"designId": d, "view": "exploded", "path": os.path.join(IMG, "exploded.png")})
    ask(24, "Make an exploded view for our design review poster, with the parts list.", ["render_3d"])
    w("![Exploded 3-D view of Maple 10K: airframe pieces pulled apart, fins slid out, every internal part laid out below "
      "the piece it goes in, with numbered balloons and a parts list with masses](examples/exploded.png)")
    w()
    heavy = sorted(ex["parts"], key=lambda p: -float(re.sub(r"[^\d.]", "", lead(p["mass"])) or 0)
                   * (1000 if lead(p["mass"]).endswith("kg") else 1))[:3]
    w(f"{len(ex['parts'])} parts, each with OpenRocket's mass (the weighed values from the mass budget). The heaviest: "
      + ", ".join(f"{p['name']} ({lead(p['mass'])})" for p in heavy) + ". Ask for `cutaway` to see them in place instead.")
    w()
    an = call("animate_flight", {"designId": d, "path": os.path.join(IMG, "flight.gif"), "duration": 22, "fps": 10,
                                 "gifWidth": 560, "mp4": False})
    ask(25, "Animate the flight for our social media post.", ["animate_flight"])
    w("![3-D animation of the simulated flight with the flight clock, altitude, speed, Mach and distance from the pad, "
      "captions at burnout, apogee and each deployment](examples/flight.gif)")
    w()
    w(f"A {an['length'].split(',')[0]} loop (GIF; an MP4 too when ffmpeg is installed), and a sheet of stills that Claude "
      "shows in the chat:")
    w()
    w("![Key moments: liftoff, burnout, apogee, drogue, main, touchdown](examples/flight-keyframes.png)")
    w()
    w("Real time through the burn, slowed down around apogee and each deployment; the coast and the long descent are "
      "sped up, with the rate on screen. Events on the timeline:")
    w()
    table(an["timeline"], ["event", "flightTime", "videoTime"], ["Event", "Flight time", "In the video"])

    # Two stages ------------------------------------------------------------------------------------------------------
    ts = call("open_design", {"example": "Two stage high power"})["designId"]
    ask(26, "We're also flying a two-stage rocket. Simulate the staging, show it pulled apart and animate it.",
        ["run_simulation", "render_3d", "animate_flight"])
    run = call("run_simulation", {"designId": ts})
    ign = [i for i in run["ignitions"] if "altitudeAtIgnition" in i]
    if ign:
        i = ign[0]
        w(f"> The {i['stage'].lower()} lights at {i['time']}, {lead(i['altitudeAtIgnition'])} up at "
          f"{lead(i['velocityAtIgnition'])} and {i['tiltFromVerticalAtIgnition']} off vertical (thrust-to-weight "
          f"{i['averageThrustToWeight']}); apogee {lead(run['flight']['apogee'])}.")
    for b in run["branches"]:
        w(f"> - {b['branch']}: lands {lead(b['landingDistanceFromPad'])} from the pad at {lead(b['groundHitVelocity'])}.")
    w()
    call("render_3d", {"designId": ts, "path": os.path.join(IMG, "two-stage-exploded.png"), "width": 1400})
    w("![Exploded view of a two-stage rocket: the sustainer and booster bracketed with their masses, every part "
      "numbered](examples/two-stage-exploded.png)")
    w()
    call("animate_flight", {"designId": ts, "path": os.path.join(IMG, "two-stage-flight.gif"), "duration": 18,
                            "fps": 10, "gifWidth": 560, "mp4": False})
    w("Each stage is followed through the whole flight. The captions name the stage (booster burnout, booster "
      "separation, sustainer ignition, sustainer burnout). A booster camera in the lower left follows the dropped "
      "booster down under its own chute. The trajectory inset draws its path in orange, and the flight summary lists "
      "where each stage lands.")
    w()
    w("![Two-stage flight animation with the booster camera](examples/two-stage-flight.gif)")
    w()
    w("![Key moments of the two-stage flight: liftoff, booster separation, sustainer burnout, apogee, main, touchdown]"
      "(examples/two-stage-flight-keyframes.png)")
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
