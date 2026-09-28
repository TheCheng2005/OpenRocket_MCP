# Examples: what you ask, what you get

One rocket, from a blank page to launch day. Each step shows what a team member types, what Claude answers, and the
numbers and plots behind the answer. **Everything below is real output from the server** (OpenRocket 24.12), generated
by [`scripts/make_examples.py`](../scripts/make_examples.py); the one-line answers are written from those numbers.
Units follow the team setting (here metric with imperial in brackets).

The rocket: *Maple 10K*, a 4 in fiberglass, dual-deploy, single-stage rocket for the 10,000 ft category of Launch
Canada 2027. Try it yourself: open [`examples/maple-10k-pdr.ork`](examples/maple-10k-pdr.ork) (the early version, before
step 3) or [`examples/maple-10k.ork`](examples/maple-10k.ork) (the finished design) and ask the same questions.

## Design

### 1. "Design a 4 inch fiberglass rocket with a 75 mm motor mount, dual deploy: 18 in drogue at apogee, 60 in main at 1000 ft. 18 in ogive nose, 64 in of airframe, four trapezoidal fins."

<sub>Tools Claude uses: `open_design`, `add_component`, `set_deployment`</sub>

Claude builds the rocket part by part in OpenRocket, inside as well as out: nose bulkhead, motor mount with three centering rings, rail buttons, both parachutes (with realistic packed sizes) and their shock cords. It opens in the OpenRocket app too.

### 2. "Lay out the avionics bay between the two airframes: two independent altimeters, a GPS tracker, and the ejection charges."

<sub>Tools Claude uses: `add_avionics_bay`</sub>

> Av-bay: 25.4 mm (1 in) switch band, 304.8 mm (12 in) coupler between Upper airframe and Lower airframe: 2 independent altimeter circuits (each: altimeter + own battery + own physical switch), GPS tracker on its own battery in the nose; main primary + backup on the forward bulkhead, drogue primary + backup on the aft bulkhead. Bay mass 567.6 g (1.251 lb). Static ports: bay volume 135 in3: 4 ports of 0.145 in (3.7 mm) evenly around the switch band (same area as 1 x 1/4 in).

<details><summary>Bay layout</summary>

| Part | Position | Mass |
|---|---|---|
| Main charge (primary) | 901.7 mm from the nose tip | 12 g |
| Main charge (backup) | 901.7 mm from the nose tip | 12 g |
| U-bolt (main harness) | 906.8 mm from the nose tip | 30 g |
| Sled, threaded rods and nuts | 939.3 mm from the nose tip | 120 g |
| 9 V battery (primary altimeter) | 943.5 mm from the nose tip | 46 g |
| Primary altimeter | 995.1 mm from the nose tip | 25 g |
| Screw switch (primary altimeter) | 1067 mm from the nose tip | 10 g |
| Screw switch (backup altimeter) | 1067 mm from the nose tip | 10 g |
| Backup altimeter | 1079 mm from the nose tip | 25 g |
| 9 V battery (backup altimeter) | 1168 mm from the nose tip | 46 g |
| Drogue charge (primary) | 1232 mm from the nose tip | 12 g |
| Drogue charge (backup) | 1232 mm from the nose tip | 12 g |
| U-bolt (drogue harness) | 1232 mm from the nose tip | 30 g |

</details>

> ⚠️ not enough room in Upper airframe: the charges, shock cord and parachute need 520.1 mm (20.48 in) from the av-bay but only 508 mm (20 in) is free before the nose shoulder. Lengthen the airframe, shorten the coupler, or use a smaller-packing parachute.

> ⚠️ not enough room in Lower airframe: the charges, shock cord and parachute need 298 mm (11.73 in) from the av-bay but only 254 mm (10 in) is free before the motor mount. Lengthen the airframe, shorten the coupler, or use a smaller-packing parachute.

*"Lengthen the upper airframe to 26 in and the lower airframe to 43 in."* The bay packs the parachutes against itself and the motor mount, fins and rail buttons are positioned from the aft end, so everything keeps its place and the parachutes get the room.

The layout follows the Launch Canada electronics edicts: one altimeter per circuit, each with its own battery and physical switch; the tracker on its own battery; charges on the bulkhead facing the bay they open. Masses are typical defaults: give Claude your actual altimeters, batteries and tracker (name, mass, length) and it uses them.

### 3. "Which motor gets us closest to 10,000 ft?"

<sub>Tools Claude uses: `rank_motors`, `set_motor`</sub>

> **AeroTech L1250DM**, 10,116 ft predicted. 68 distinct motors fit (diameter, length + 50 mm (1.969 in) overhang, filters); 60 simulated; 57 meet the rules: each one was flown in the simulation, not just looked up, and motors that break a rule (rail exit speed, thrust-to-weight, stability) are ranked last.

| Motor | Impulse | Apogee (vs 10,000 ft) | Rail exit | Max Mach | Min stability | Meets rules |
|---|---|---|---|---|---|---|
| AeroTech L1250DM | L, 4400 Ns (989.2 lbf-s) | 3083 m (10116 ft) (+35.46 m (116.3 ft)) | 39.48 m/s (129.5 ft/s) | 1.165 | 1.921 cal | yes |
| AeroTech L1170FJ | L, 4214 Ns (947.4 lbf-s) | 2985 m (9792 ft) (-63.36 m (-207.9 ft)) | 40.26 m/s (132.1 ft/s) | 1.208 | 1.743 cal | yes |
| AeroTech L1940X | L, 4317 Ns (970.5 lbf-s) | 3157 m (10359 ft) (+109.4 m (358.8 ft)) | 54.77 m/s (179.7 ft/s) | 1.46 | 2.044 cal | yes |
| Cesaroni Technology 3757L800-P | L, 3723 Ns (836.9 lbf-s) | 2937 m (9636 ft) (-110.8 m (-363.6 ft)) | 38.61 m/s (126.7 ft/s) | 1.054 | 1.848 cal | yes |
| Cesaroni Technology 4263L1350-P | L, 4280 Ns (962.2 lbf-s) | 3167 m (10390 ft) (+118.8 m (389.9 ft)) | 45.42 m/s (149 ft/s) | 1.386 | 1.911 cal | yes |
| AeroTech L1390G | L, 3946 Ns (887.2 lbf-s) | 2925 m (9598 ft) (-122.5 m (-401.9 ft)) | 44.64 m/s (146.4 ft/s) | 1.266 | 1.889 cal | yes |

### 4. "It goes supersonic. Will our 1/8 in fins flutter?"

<sub>Tools Claude uses: `fin_flutter`</sub>

> **FAIL.** At 393.4 m/s (1291 ft/s) (Mach 1.165) and 661.7 m the estimated flutter speed is only 163.1 m/s: margin 0.4147 against the team's required 1.5. Fins at least **7.54 mm (0.2969 in)** thick, a stiffer material or a lower aspect ratio fix it.

### 5. "We'll make the fins from 1/4 in quasi-isotropic carbon. Our coupon test gave a shear modulus of 16 GPa: save that to our standards and check again."

<sub>Tools Claude uses: `update_standards`, `edit_components`, `fin_flutter`</sub>

> **PASS**, margin 2.905 (shear modulus 16 GPa from your standards file, so every later check and the whole team use the measured value).

### 6. "It has to meet the stability rules (also in 30 km/h wind): add the nose weight it needs, then make the fins as small and light as possible while staying clear of flutter, and pick the motor again."

<sub>Tools Claude uses: `ballast`, `add_component`, `optimize`, `rank_motors`, `fin_flutter`</sub>

> **390 g of nose ballast**, then fin span **178.9 mm**, root chord **178.4 mm** (meets every constraint): stability 2.192 cal to 5.13 cal in flight, flutter margin 2.203. Best motor now: **Cesaroni Technology 6162M1675-P**, 9,979 ft, Mach 1.383. Final flutter check: **PASS**, margin 1.626.

<sub>Why ballast: with the real avionics bay modelled, the electronics sit further aft than a single lump would, so the CG moves back. Limits used: minStability 2.189 cal = max(1.5 cal, 10% of body length at L:D 21.89); maxStability 6 cal (over-stable); flutter margin ≥ 1.5 from the team standards.</sub>

### 7. "Show me the rocket."

<sub>Tools Claude uses: `draw_rocket`</sub>

![Maple 10K cut-away: nose with GPS tracker, main and shock cord, the avionics bay with both altimeter circuits and charges, drogue, motor mount and motor, with the separation points, CG and CP marked](examples/rocket.svg)

A cut-away from the OpenRocket model itself: every part is drawn where it is, electronics coloured by role, and the red dashes show where the rocket separates (main at the nose, drogue below the avionics bay).

88.98 in long, 10.19 kg (22.46 lb) on the pad, stability 3.837 cal at launch and 5.009 cal at burnout.

## Recovery

### 8. "What main do we need to land at 20 ft/s? And a drogue for about 85 ft/s."

<sub>Tools Claude uses: `size_parachute`, `edit_components`</sub>

> The descending mass is 7.03 kg (15.5 lb), so the main needs a drag area of 3.029 m2 (32.6 ft2) (a 2196 mm flat canopy at Cd 0.8). Parachutes from OpenRocket's catalogue that do it:

| Parachute | Diameter | Cd | Descent | Packed size | Mass |
|---|---|---|---|---|---|
| Rocketman DG-07 | 2134 mm (84 in) | 0.85 | 6.086 m/s (19.97 ft/s) | 101.6 mm (4 in) dia x 185.4 mm (7.3 in) = 1503 cm3 (91.73 in3) | 442.8 g (15.62 oz) |
| Rocketman LA-07 | 2134 mm (84 in) | 0.85 | 6.086 m/s (19.97 ft/s) | 76.2 mm (3 in) dia x 188 mm (7.4 in) = 857.2 cm3 (52.31 in3) | 252.3 g (8.9 oz) |
| Rocketman EL-060 | 1524 mm (60 in) | 1.6 | 6.21 m/s (20.37 ft/s) | 101.6 mm (4 in) dia x 101.9 mm (4.01 in) = 825.8 cm3 (50.39 in3) | 280.7 g (9.9 oz) |
| Fruity Chutes CFC-060-N | 1524 mm (60 in) | 1.55 | 6.309 m/s (20.7 ft/s) | 101.6 mm (4 in) dia x 134.6 mm (5.3 in) = 1091 cm3 (66.6 in3) | 283.5 g (10 oz) |

*"Use the Rocketman DG-07 and a 21 in drogue."*

### 9. "What loads do the chutes see when they open, and how many 4-40 nylon shear pins do we need?"

<sub>Tools Claude uses: `recovery_analysis`, `ejection_charge`</sub>

| device | opens at | design load | harness rating | descent |
|---|---|---|---|---|
| Drogue | 3044 m, 19.45 m/s | 70.13 N (15.77 lbf) | 140.3 N | 26.46 m/s (86.81 ft/s) |
| Main | 299.2 m, 25.7 m/s | 1673 N (376 lbf) | 3345 N | 5.979 m/s (19.61 ft/s) |

> Shear pins on the main bay: **3 x 4-40 nylon** (they must hold while the drogue opens). Black powder for a 4 × 12 in bay at 15 psi: **1.75 g** primary, 2.187 g backup. The harness rating is twice the opening load, for shock cord, quick links and eye bolts.

## Flight and rules

### 10. "Does it pass Launch Canada?"

<sub>Tools Claude uses: `check_requirements`</sub>

> **No failures; 4 warning(s).** Every check cites its rule, and there is a checklist of 15 things to verify by hand (electronics, radio, structures, operations).

|  | Check | Value | Rule |
|---|---|---|---|
| WARN | Launch site altitude | 0 m (0 ft) used by this simulation |  |
| WARN | Ascent stability (maximum, over-stability) | 5.712 cal | R10.3.1, R10.4.1 |
| WARN | Ascent stability in 8.333 m/s (27.34 ft/s) wind (maximum, over-stability) | 5.713 cal | R10.3.1, R10.4.1 |
| WARN | Maximum Mach number | 1.372 |  |
| PASS | Simulated launch angle | 6 deg | R10.1.1 |
| PASS | Rail departure velocity | 45.75 m/s (150.1 ft/s) | R10.2.1 |
| PASS | Thrust-to-weight at liftoff (avg thrust / liftoff weight) | 16.59 | R10.2.2, R3.1.2, R3.1.3; 2027 Edicts (Advanced Specific) |
| PASS | Ascent stability (minimum, rail exit to apogee while airspeed > 100 ft/s) | 3.528 cal at t=0.296 s, Mach 0.1353 | R10.3.1, R10.4.1 |
| PASS | Ascent stability in 8.333 m/s (27.34 ft/s) wind (minimum, rail exit to apogee while airspeed > 100 ft/s) | 2.473 cal at t=0.296 s, Mach 0.1401; angle of attack 11.54 deg there (wind / rail exit): OpenRocket's CP moves forward at high angle of attack. Zero-AoA margin: 3.978 cal | R10.3.1, R10.4.1 |
| PASS | Fin flutter margin (Fins, Carbon fiber) | 1.638 at 817.5 m (2682 ft), 462.4 m/s (1517 ft/s) | structures.flutterMinMargin |
| PASS | Length-to-diameter ratio | 22.13 | 2027 Edicts, Stability: L:D Ratio |
| PASS | Damping ratio during ascent (airspeed > 100 ft/s) | 0.06625 (t=17.95 s, Mach 0.09305) to 0.08012 (t=3.991 s) | 2027 Edicts, Stability: Damping ratio |

### 11. "Simulate the flight and plot it."

<sub>Tools Claude uses: `run_simulation`</sub>

> Apogee **3044 m (9987 ft)** at 20.35 s. Top speed 462.2 m/s (1516 ft/s) (Mach 1.372), 19.89 G peak, 45.75 m/s (150.1 ft/s) off the rail.

![Altitude against time with burnout, apogee and both deployments marked](examples/flight-profile.svg)

### 12. "Where will it land in a 15 km/h west wind? Include our build and motor uncertainty."

<sub>Tools Claude uses: `monte_carlo`</sub>

> Over 200 simulated flights the median landing is 185.2 m (607.5 ft) from the pad and 95% land within 473 m (1552 ft); the landings centre 359 ft west of the pad (the rail is tilted into the wind, so it flies upwind and drifts back under the drogue). Apogee 3033 m (9952 ft) ± 216.7 m (711 ft).

![200 simulated landings around the pad with the 2-sigma ellipse](examples/landing.svg)

Claude also reports what drives the spread (correlation of each uncertain input with the result), so the team knows what to measure more carefully:

| Uncertain input | Apogee | Min stability | Landing distance |
|---|---|---|---|
| windSpeed | -0.09698 | -0.9628 | -0.5445 |
| launchAngle | -0.1324 | -0.007481 | 0.2238 |
| structureMass | -0.05403 | 0.146 | 0.09483 |
| airframeDrag | -0.9325 | 0.03553 | -0.1818 |
| motorThrust | 0.3213 | 0.07204 | -0.007788 |
| parachuteCd | -0.03153 | 0.03382 | -0.02587 |

<sub>Correlation from -1 to 1: the closer to ±1, the more that input drives the result.</sub>

## Design studies

### 13. "Would a different nose cone or fin shape fly higher?"

<sub>Tools Claude uses: `compare_shapes`</sub>

> Best that keeps the stability: **tangent ogive (current) nose with airfoil fin edges: 3701 m (12142 ft) (+21.57% vs current)**. Every nose profile and fin edge was flown; CD at the design Mach (1.372) shows where the gain comes from.

| Nose | Fin edges | Apogee | vs current | CD | Min stability |
|---|---|---|---|---|---|
| tangent ogive (current) | airfoil | 3701 m (12142 ft) | 21.57% | 0.8569 | 3.628 cal |
| tangent ogive (current) | rounded | 3552 m (11655 ft) | 16.69% | 0.9075 | 3.535 cal |
| tangent ogive (current) | square (current) | 3044 m (9987 ft) | 0% | 1.064 | 3.528 cal |
| Von Karman (Haack LD) | square (current) | 3032 m (9947 ft) | -0.404% | 1.114 | 3.531 cal |
| 1/2 power | square (current) | 3032 m (9946 ft) | -0.413% | 1.109 | 3.543 cal |
| 1/2 parabola | square (current) | 3021 m (9912 ft) | -0.7564% | 1.111 | 3.506 cal |
| 3/4 power | square (current) | 3021 m (9911 ft) | -0.761% | 1.114 | 3.515 cal |
| LV-Haack | square (current) | 3018 m (9902 ft) | -0.8531% | 1.13 | 3.541 cal |

<details><summary>Claude's shape guidance (from the tool)</summary>

- Subsonic (< Mach 0.8): skin friction dominates; nose shape changes apogee by ~1-3%. Tangent ogive or elliptical are easy to make and near-optimal; a smooth finish and filled fin joints matter more.
- Transonic / supersonic: theory favours Von Karman / LV-Haack and a longer nose (5-6 calibers) for wave drag, and blunt shapes (elliptical, 1/2 power) lose. OpenRocket uses empirical per-shape curves, so at modest fineness a tangent ogive can rank equal or better in this table: trust the table for this vehicle, and the theory only as a tie-breaker.
- Fin edges: airfoiled or beveled edges reduce fin pressure drag, most at high speed; they also lower the fin's thickness where it is thin, so re-check fin_flutter. Swept, clipped-delta planforms suit supersonic flight.
- OpenRocket's nose and fin pressure-drag models are empirical and least accurate transonic; confirm close calls with RASAero (import_aero_table) or CFD before committing tooling.

</details>

## Reviews

### 14. "Make the design review package."

<sub>Tools Claude uses: `generate_report`</sub>

Claude writes a folder with `report.md` (requirement checks, vehicle, flight, stability, recovery, wind table, methods), the stability-vs-time plots Launch Canada asks for (DTEG R10.3.2), the drawing, the flight profile and a CSV of the flight data.

![Stability margin during the ascent with the rule minimum](examples/stability-ascent.svg)

### 15. "What changed since the version we showed at PDR?"

<sub>Tools Claude uses: `compare_designs`</sub>

- launch mass: 9.652 kg (21.28 lb) -> 10.31 kg (22.73 lb) (+658.8 g (1.452 lb) (+6.825%))
- static stability at launch: 2.508 cal -> 3.934 cal (+1.426 cal)
- apogee: 3083 m (10116 ft) -> 3044 m (9987 ft) (-39.3 m (128.9 ft) (-1.275%))
- max Mach: 1.165 -> 1.372 (+0.2065)
- rail exit velocity: 39.48 m/s (129.5 ft/s) -> 45.75 m/s (150.1 ft/s) (+6.262 m/s (20.55 ft/s) (+15.86%))
- min stability in flight: 1.921 cal -> 3.528 cal (+1.607 cal)
- 9 rule check(s) changed status, 2 got worse
- 4 component(s) added, removed or edited

- **Main (Parachute)**: area 1.824 m2 (19.63 ft2) -> 3.575 m2 (38.48 ft2); cD 0.8 -> 0.85; diameter 1524 mm (60 in) -> 2134 mm (84 in)
- **Fins (Trapezoidal Fin Set)**: height 127 mm (5 in) -> 178.9 mm (7.045 in); material Fiberglass -> Carbon fiber; rootChord 228.6 mm (9 in) -> 178.4 mm (7.023 in); sweepAngle 45 deg -> 35.37 deg; thickness 3.2 mm (0.126 in) -> 6.35 mm (0.25 in)
- **Drogue (Parachute)**: area 0.1642 m2 (1.767 ft2) -> 0.2235 m2 (2.405 ft2); diameter 457.2 mm (18 in) -> 533.4 mm (21 in)

## Launch day

### 16. "We launch at 48.47, -81.33 on August 21 at 3 pm. What will the winds do, and make us the flight card."

<sub>Tools Claude uses: `weather_forecast`, `flight_card`</sub>

> Ground wind 6.75 m/s (22.15 ft/s) (24.3 km/h) from 250 deg, gusts 12 m/s (39.37 ft/s) (43.2 km/h) (mean wind within 30 km/h limit; gusts exceed it). Flown in the forecast winds aloft: apogee 3095 m (10154 ft), landing 182.4 m (598.6 ft) from the pad.

<sub>(A recorded forecast is used here so the page is reproducible; with internet access Claude fetches the live one from Open-Meteo.)</sub>

Winds aloft from the forecast, flown as a wind profile:

- 10 m (32.81 ft) AGL: 6.75 m/s (22.15 ft/s) from 250 deg
- 180 m (590.6 ft) AGL: 9.79 m/s (32.12 ft/s) from 255 deg
- 690 m (2264 ft) AGL: 5.65 m/s (18.54 ft/s) from 262.5 deg
- 2710 m (8891 ft) AGL: 9.02 m/s (29.59 ft/s) from 267.5 deg
- 6880 m (22572 ft) AGL: 15.97 m/s (52.4 ft/s) from 278 deg
- 11480 m (37664 ft) AGL: 23.63 m/s (77.53 ft/s) from 289.5 deg

From the one-page flight card (predictions, motor delay, deployment settings, drift in each wind, rule check and sign-off lines):

> **Predicted flight**
>
> | | |
> |---|---|
> | Apogee | 3095 m (10154 ft) |
> | Time to apogee | 20.49 s |
> | Max velocity | 469.8 m/s (1541 ft/s) |
> | Max Mach | 1.374 |
> | Max acceleration | 196.2 m/s2 (643.7 ft/s2) = 20.01 G |
> | Rail exit velocity | 45.73 m/s (150 ft/s) |
> | Thrust-to-weight | 16.57 |

## After the flight

*"Here is our altimeter file — how did we do compared with the prediction?"* Claude lines the log up with the simulation (`compare_flight`), fits the drag so the next prediction is closer, and plots simulated against measured altitude.
