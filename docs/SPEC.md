# OpenRocket MCP — design and roadmap

## Goal

Let a student competition team state **goals** and have Claude reach them with real physics:

- "Land under 20 ft/s" → which parachute, which shock cord, will it fit, what loads.
- "Find the right motor" → search + simulate candidates against apogee and rule constraints.
- "Optimize CP/CG" → keep 1.5–2 cal through the whole ascent (and for the sustainer after staging) at minimum cost in
  apogee.
- Any vehicle: single or multi-stage, commercial, research or **custom (liquid / hybrid)** engines.

## Principles

1. **The server does the numbers, Claude does the intent.** Iterative numeric work (sizing, ranking, sweeps, delay
   studies) runs server-side in one tool call. Claude turns goals into constraints, weighs trade-offs and explains.
2. **Simulation inputs over hand estimates.** Deployment airspeed, density and mass come from the OpenRocket simulation.
3. **Team standards are data.** Safety factors, pin ratings, packing factors, Cx and the launch site live in a committed
   JSON file so the whole team gets reproducible answers.
4. **Rules are data.** Competition thresholds live in a rule-set JSON with section references (Launch Canada DTEG R4
   built in), so the next revision is a data change.
5. **Honest outputs.** Every number is simulated, calculated or assumed, with the method stated; nothing replaces
   ground tests.
6. **Standalone on published OpenRocket.** Depends on `info.openrocket:core` from Maven Central instead of forking
   OpenRocket, so upgrading OpenRocket is a version bump.

## Architecture

```
Claude (Code / Desktop)  ──stdio JSON-RPC──▶  McpServer (tools, prompts, resources)
                                                 │
                         ┌───────────────────────┼─────────────────────────┐
                    tools/*Tools            or/* (OpenRocket)          calc/* (pure math)
                  (argument parsing,     Designs, Components,       Atmosphere, Parachutes,
                   units, rendering)     Analysis, Sims, Motors,    OpeningShock, Charges,
                                         Recovery, Requirements     Packing
                                                 │
                                   info.openrocket:core 24.12 (headless)
```

- `units/` — parsing ("20 ft/s") and display in metric / imperial / both.
- `standards/` — team standards merged over defaults + rule set.
- Designs are held in memory; `save_design` writes .ork files usable in the OpenRocket app.
- Custom engines are written as RockSim `.rse` files (per-point mass and CG) and loaded through OpenRocket's own loader.

## Status

### Phase 1 — done (v0.1.0)

- Design open/create/save, component tree, reflection-based property editing, add/remove components, deployment and
  stage-separation events, flight configurations.
- Static stability per stage stack (full vehicle, then each stack after a separation).
- Simulation summary per branch (stage): rail exit, TWR, stability min/max during ascent, air-start tilt/altitude,
  deployments, descent rates, landing distance; down-sampled flight data; parameter sweeps (conditions or any property).
- Motors: search (incl. certification level), simulated ranking, import .eng/.rse, custom liquid/hybrid engines with
  tank CG shift.
- Recovery chain: deployment conditions from the sim, opening load (Knacke Cx and finite-mass inflation, floored at
  steady drag), shear pins, deployment-delay sweep against pin capacity, parachute sizing with real presets, bay volume
  fit, black powder charges (primary/backup, charge-well limit), landing energy per section.
- Launch Canada DTEG R4 requirement check, including the 30 km/h wind case and staged-flight rules.
- Claude Code (`.mcp.json`, skill) and Claude Desktop setup; CI on Windows/macOS/Linux.

### Phase 2 — done (v0.2.0)

- **Performance**: array-backed, cached flight-data access (OpenRocket's `DataBranch.get()` copies a whole column per
  call, which made per-point lookups quadratic); what-if simulations run on rocket copies in a thread pool
  (~3x faster on 4 cores) and never touch the open design.
- **Repeatability**: OpenRocket seeds wind turbulence from `new Random()` when simulation options are created and
  `setRandomSeed()` does not reach the wind model; variants are re-seeded explicitly (common random numbers for
  comparisons, per-run seeds for Monte Carlo).
- **optimize**: parallel goal-seeking over 1-3 component properties (bracketing grid in 1D, shrinking Latin
  hypercube in 2-3D) with stability / rail-exit / Mach / apogee constraints; reports the current design, the best
  point and alternatives; optional apply.
- **monte_carlo**: landing ellipses per stage, apogee spread, worst ascent stability and rail exit with rule
  violation counts, worst deployment airspeed and opening load per device.
- **Reports**: `generate_report` (Markdown + stability-vs-time SVG plots for DTEG R10.3.2, with on-rail stability
  filled in from Barrowman CP and simulated CG) and `export_flight_data` (CSV).
- **Bay volume from the design**: interior volume of body tubes, nose cones and transitions.

### Phase 2.5 — dogfooding fixes (v0.3.0)

Found by building a 10k ft Launch Canada rocket from scratch through the tools, then encoded in
`scripts/benchmark.py` (30 checks, run in CI):

- Safety: recovery devices deploying before apogee (OpenRocket's default "motor ejection charge" event) or more than
  3 s after it are flagged in check_requirements, rank_motors and recovery_analysis; supersonic flight warns about fin
  flutter and aerodynamic accuracy.
- rank_motors: works on a new design, filters motors that do not fit the mount length, removes duplicates, reports
  ascent stability with each motor's mass, ranks rule-compliant motors first, and samples twice (spread, then around
  the target impulse).
- optimize: constraints checked at the rule set's maximum wind as well; infeasible results name the binding limits;
  a repair line-search crosses thin feasible bands.
- Consistent turbulence: every run derives its gusts from the simulation seed, so check_requirements, optimize and
  sweeps agree.
- Recovery: shear pins only for bays that must stay closed during an earlier event; harness working load for all.
- Stability failures report the angle of attack and the zero-AoA margin at the minimum.
- Editing: atomic add_component, materials by name, student-style aliases (span, sweepLength, mass), sweep is a
  length.
- Output size: compact JSON and a one-line-per-component tree (-33% tool output).

### Launch Canada 2027 edicts (v0.4.0)

From the "LC 2027 DTEG and R&R Edicts" (to become DTEG R5), rule set `launch-canada-2027` (now the default):

- Length-to-diameter ratio (shall ≤ 45, should ≤ 25).
- Damping ratio along the ascent (shall 0.03–0.5, should 0.05–0.3): Barrowman/Apogee formulation with OpenRocket's
  per-component CNα and CP (verified to reproduce OpenRocket's vehicle CP), simulated inertia and jet damping; the
  vehicle configuration follows staging.
- Static margin ≥ 10% of body length from rail exit to twice each stage's burn time (the stack after separation uses
  its own length); `optimize` with `meetRules` derives the floor max(1.5 cal, 10% x L:D).
- Metal rail buttons prohibited; SRAD/hybrid/liquid static-fire Isp ≥ 100 s; concentric avionics reminder above
  Mach 0.7; pop-test list for the dress rehearsal; manual checklist for electronics, radio allocations, structures,
  SRAD test sequence and operations.
- `pressure_vessel` and `advanced_probation` tools (GLPP probation levels, AASI).

### Structures, ballast, vehicle dispersion (v0.5.0)

- `fin_flutter`: NACA TN 4197 flutter speed per fin set at every point of the simulated flight (local pressure and speed
  of sound), with the corrected constant (Peak of Flight #615); booster fins until separation; thickness / shear
  modulus to reach the team margin. Fin-material shear moduli and the required margin are team standards
  (`structures.*`). Also a check_requirements item (team standard, not a rule).
- `ballast`: nose weight for a minimum simulated ascent stability (default: the rule-set floor), analytic first guess
  corrected by the gap between static and simulated margin, then secant iterations on simulations; reports the apogee
  and rail-exit cost.
- `monte_carlo` part 2: structure mass (per-component scaling, motors excluded), airframe drag and motor thrust
  (simulation listeners), per-parachute Cd; `drivers` = correlation of each randomized input with apogee, minimum
  stability and landing distance.
- Weighed-mass overrides: warnings from edit_components, add_component, sweep and optimize when a section's
  subcomponent mass/CG override hides the change; ballast raises the override.
- Report: wind-sensitivity table (0 to the rule-set maximum wind) for the flight card.
- Tests: 31 -> ~170 (protocol, standards merging, SVG well-formedness, physics property tests, requirements verdicts,
  Monte Carlo determinism and listeners, flutter and ballast against hand calculations). Bugs found by the new tests:
  saved standards dropped null keys; ballast returned 0 kg when only the simulated minimum was short; team material
  keys lost to overlapping default keys.

### Deeper OpenRocket (v0.6.0)

- `aero_analysis`: BarrowmanCalculator queried directly — total CD split into friction / pressure / base, CP, CNalpha,
  launch and burnout margins vs Mach, per-component drag at one Mach, OpenRocket's geometry warnings. Also a table in
  the report.
- `wind_profile`: OpenRocket's multi-level wind model (levels from a forecast or sounding, or a power-law shear profile);
  per-level turbulence seeded for repeatability; wind overrides scale / rotate the profile.
- `search_parts` / `apply_preset`: the full parts database (all ComponentPreset types), filters on diameter, maker,
  text and material; presets load dimensions, material and mass.
- `draw_rocket`: side profile from OpenRocket geometry (body radius profiles, fin outlines, pods) with CG / CP; in the
  report as rocket.svg.
- OpenRocket 24.12 quirks found and handled: `SimulationOptions.getWindSpeedAverage()` (and the direction, turbulence
  and deviation getters) select the average wind model, so merely reading the wind discarded a profile; the launch CG
  from `MassCalculator` changes over the first calls after loading (lazy position resolution) — designs are settled on
  open and before analyses / simulations.

### External data (v0.7.0)

- `import_aero_table`: RASAero II aero export (lowest-alpha rows, CD power-off / power-on, CP in inches) or plain
  Mach / CD [/ CP] CSV. A simulation listener replaces OpenRocket's axial CD (power state from thrust) until the first
  separation; the CP gives a stability item in check_requirements. Validated by importing a table generated from
  OpenRocket's own CD (apogee within 3%).
- `compare_flight`: altimeter CSV (header or explicit units, pad altitude removed, launch alignment), apogee / time to
  apogee / drogue and main descent-rate comparison, overlay SVG, and the airframe drag factor that reproduces the
  measured apogee (parallel sims over 0.5-2x). Validated closed-loop: a flight flown with 1.3x drag is recovered as 1.3.

### Design studies (v0.8.0)

- `compare_shapes`: nine nose profiles (conical, tangent ogive, elliptical, 1/2 and 3/4 power, parabolic, 1/2 parabola,
  Von Karman, LV-Haack), optional nose lengths, and fin edge profiles flown in parallel on copies; CD at one design Mach
  for all; best feasible option and best nose + best fins combined; explicit "none" when nothing meets the stability
  floor.
- `recovery_sections`: sections split at the forward end of every bay (or named joints); masses from OpenRocket's own
  per-component mass analysis (weighed-section overrides scaled) plus burnt-out motor cases - they add up to the
  simulated landing mass of each stage; landing energy vs `recovery.maxLandingEnergy`, drogue-only contingency, bay fill
  from packed dimensions, black powder estimate.
- `structural_loads`: N(x) = m_fwd/m (T - D) + D_fwd over boost and coast; bending at max q with a gust and at the
  largest simulated q·sin(AoA), with inertial relief (rigid body); per stack phase (full vehicle, then each stack after
  separation); thin-wall stress, required allowable with `structures.loadSafetyFactor`, optional margin. Verified by
  equilibrium: the bending moment vanishes past the tail and the axial force there equals thrust.

### Launch day, heating, roll (v0.9.0)

- `weather_forecast`: Open-Meteo hourly forecast by coordinates (asks for them when the standards do not have them):
  10 m wind, gusts, 2 m temperature, surface pressure, 80/120/180 m winds and 16 pressure levels 1000-100 hPa with
  geopotential heights (levels below ground or inside the near-surface band dropped); applied as an AGL wind profile
  plus site altitude, temperature and pressure; `forecastJson` fallback when the server is offline. Tested against a
  local HTTP server serving a recorded-format response.
- `flight_card`: one-page Markdown card from the simulation in the day's conditions.
- `aero_heating`: stagnation / recovery temperature and Sutton-Graves nose-tip flux along the flight vs
  `structures.maxServiceTemperature`.
- `roll_analysis`: cant sweep on copies; roll rate vs pitch natural frequency sqrt(C1 / I) / 2 pi (roll resonance).

### Design diff and team access (v0.10.0)

- `compare_designs`: baseline = open design, .ork file or git revision (`git show REV:./file`, revision names
  validated, no option injection). Both flown in the reviewed design's conditions (options copied, multi-level wind
  profile copied with its altitude reference). Components matched by persistent id, else type + name; property
  changes from the same reflection `describe_component` uses. Rule checks compared by item.
- Team server: `HttpTransport` (JDK `HttpServer`), token by header or URL path (constant-time compare), Origin check,
  per-design `ReentrantLock` guard in `McpServer`, `Context.path` sandbox (normalised prefix + real-path check for
  symbolic links), rule-set files limited to the workspace, workspace-relative output filter, files page.
- `.mcpb` extension with a `jlink` runtime; manifest generated from the tool registry and validated with the `mcpb`
  CLI; extension smoke test in CI on four platforms.
- RASAero / aero tables saved beside the design (`NAME.aero.json`) and restored on open.

### Examples and plots (v0.11.0)

- `docs/EXAMPLES.md` generated by `scripts/make_examples.py` from real runs (one 10,000 ft rocket from design to
  launch day), rebuilt in CI.
- Landing map (`monte_carlo plotPath`) and flight profile (`run_simulation plotPath`, report).
- Optimizer: fin flutter margin per point, `minFlutterMargin` constraint, on with `meetRules`.
- Fixes found by the examples: `set_deployment` / `set_stage_separation` with `configuration: all` before any motor
  (no configurations yet) now set the default for configurations created later; motors that list no ejection delay
  get a plugged delay instead of NaN, which made every simulation fail; design diff ignores override values that are
  switched off.

### Phase 3 — next

- More rule sets (Spaceport America Cup / IREC, NASA Student Launch) as JSON.

### Phase 4 — later

- Live control of the OpenRocket GUI via an OpenRocket plugin (instead of a fork).
- OAuth for the team server (today: a shared token).

## Validation

- Calculators are unit-tested against published ISA values and against the worked numbers in the team's recovery
  documents (terminal velocities, opening forces, bay lengths, cord volumes, pin counts) and the 0.006·D²·L BP rule.
- End-to-end tests drive every tool through JSON-RPC on OpenRocket's two-stage example.
- Fin flutter is checked against the NACA TN 4197 form in psi units and its scaling laws (t^1.5, sqrt(G), 1/sqrt(P));
  ballast against OpenRocket's own static margin after inserting the computed mass.
- Not yet validated against flight data — compare against altimeter logs after each flight and record calibration
  (e.g. measured packing factors, canopy fill constants) in the team standards.
