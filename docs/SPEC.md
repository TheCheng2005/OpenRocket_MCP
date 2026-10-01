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
Claude / Codex  ──stdio or HTTP JSON-RPC──▶  McpServer (tools, prompts, resources)
                                                 │
                         ┌───────────────────────┼─────────────────────────┐
                    tools/*Tools            or/* (OpenRocket)          calc/* (pure math)
                  (argument parsing,     Designs, Components,       Atmosphere, Parachutes,
                   units, rendering)     Analysis, Sims, Motors,    OpeningShock, Charges,
                                         Recovery, Requirements     Packing
                                                 │
                                   info.openrocket:core 24.12 (headless)
```

- `report/` — Markdown report, flight card, SVG plots and drawings, PNG for the chat, KML; the software 3-D renderer
  (`Raster3d`), the part model (`Model3d`), exploded / cut-away views (`View3d`) and the flight animation
  (`FlightAnimation`, written by `Video` as GIF and, through ffmpeg, MP4).
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
- `draw_rocket`: cut-away from OpenRocket geometry (body radius profiles, fin outlines, pods) with the internal
  components drawn by kind (typed mass components, recovery devices, shock cords, rings, couplers, shoulders, motors at
  their overhang), separation points from the recovery sections, labels without overlap, legend, CG / CP; in the report
  as rocket.svg.
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

### Avionics and cut-away drawing (v0.12.0)

- `add_avionics_bay`: builds the bay from OpenRocket's typed mass components (ALTIMETER, BATTERY, TRACKER,
  DEPLOYMENTCHARGE, RECOVERYHARDWARE) per the 2027 electronics edicts; packs recovery against it and checks the free
  length to the nose shoulder / motor mount or motor.
- `check_requirements`: redundant deployment electronics and one power supply per altimeter and tracker.
- New parachutes get a packed size from the canopy diameter (about 0.025 in³ per in² of D²) so drawings and bay checks
  are realistic; the unit parser accepts its own "274.3 mm (10.8 in)" output.

### Fin design, FEA and CFD hand-offs (v0.13.0)

- `optimize_fins`: the optimizer takes a custom applier (shape space of buildable trapezoids) and flies candidates on
  OpenRocket's drag even with an imported table; rule-derived constraints shared with `optimize`.
- `fin_fea`: CalculiX deck (S8R, engineering constants, clamped root, flight pressure, *FREQUENCY), run when ccx is
  found ($CCX or the PATH; never an executable path from a tool argument), results read from .dat / .frd; CI installs
  CalculiX on Linux and checks the model against cantilever plate theory.
- `export_geometry`: multi-solid ASCII STL (closed and outward-oriented, checked by edge pairing and divergence-theorem
  volume), DXF R12 fin patterns, CFD run matrix and results template.
- `structural_loads csvPath`; standards gain youngsModulus, poissonRatio and strength tables.
- Fixes: `aero_analysis` drag breakdown counted one fin of a set (OpenRocket's per-component drag is per copy), and
  `structural_loads` split drag along the body the same way; unit inputs accept fractions ("1/8 in").

### Working with Claude (v0.14.0)

- MCP: per-call context (progress notifications from `Variants.runAll`, cancellation checkpoints, image content);
  stdio requests run one at a time in order on a worker thread, while the reader answers `ping` and cancellation at
  once; Streamable HTTP answers a tool call with a progress token as
  server-sent events.
- Images: our SVGs rasterized with Apache Batik (`report.Png`), best effort (no image, same text, if it fails).
- `design_status`; `undo` / `redo` / `history` (rocket copies around every editing tool, kept when OpenRocket's
  modification id changes); KML landing zones from `monte_carlo`.

### Mass tracking (v0.15.0)

- `mass_budget` (`or.MassBudget`): flexible CSV parsing, component matching (id, exact name, unique partial, word
  overlap; duplicate names reported as ambiguous), comparison and totals, apply as OpenRocket overrides.
- Fix: per-component masses with a weighed section (mass override of a component and its subcomponents) counted the
  section twice (OpenRocket keeps the override on the section's own entry); now spread over the section in proportion
  to the parts' own masses, so they add up to OpenRocket's structure mass. Affects `structural_loads` too.

### Review and cleanup (v0.15.1)

- Fixes:
  - stdio: `ping` and cancellation are recognised by parsing the request, not by searching its text;
  - simulations that were never flown no longer stay in static maps (weak maps), and closing a design frees its
    aero table unless another open copy of the same rocket uses it;
  - `mass_budget`: several lines for one part are summed (CG mass-weighted), and a part the design lacks is added when
    the line names a parent;
  - enum and unit names parse the same in every system locale (`Locale.ROOT`, e.g. Turkish);
  - OpenRocket's deprecated `getActiveComponents()` replaced.
- Cleanup: one `Xml.esc` for SVG / KML / HTML text; imports instead of fully-qualified names; `serialVersionUID`s.
- Docs: the tool reference split into one section per area, with one bullet per tool.

### 3-D views and flight animation (v0.16.0)

- `render_3d` (`report.View3d`, `report.Model3d`, `report.Raster3d`): exploded, cut-away and assembled views from
  OpenRocket's geometry, with numbered balloons and a parts list with masses. Rendering is pure Java (depth buffer,
  Gouraud shading with a crease angle, 2x2 supersampling), so it runs headless in the desktop extension.
- `animate_flight` (`or.FlightTrack`, `report.FlightAnimation`, `report.Video`, `report.Gif`): position, attitude
  (orientation theta / phi, checked against the flight path in the tests), accumulated roll, events and HUD numbers from
  the simulation. A time warp is real time in the burn and slowed around apogee and deployments. Frames are
  deterministic, so they render in parallel and stream to a GIF (one median-cut palette) and to ffmpeg for the MP4. A
  key-moment stills sheet is attached in the chat.
- Review fixes before release:
  - dropped stages (OpenRocket starts their branch at liftoff) come off at their separation event and are drawn on the
    vehicle until then;
  - flames burn at every nozzle of the lowest attached stage and of attached side boosters;
  - the pad height allows for the booster;
  - smoke thins out instead of filling the frame;
  - apogee without an event is the highest point;
  - motors are placed by their own mount;
  - vertex normals are shared safely between render threads;
  - parallel frames are capped by free memory, and very large stills skip supersampling;
  - a cancelled render stops ffmpeg and removes the partial files.

### Two-stage rockets in the 3-D views and animation (v0.16.1)

- `FlightTrack`: events name their stage when there are several (booster burnout / separation, sustainer ignition /
  burnout). Dropped stages' deployments and landings are events of their own track (`Event.branch`).
- `FlightAnimation`:
  - a dropped stage pivots on its own simulated CG and hangs under its own chute;
  - a booster camera inset follows it from separation to touchdown;
  - the chase camera pulls back at staging;
  - smoke is drawn only while a motor burns (no trail through a coast between stages);
  - the trajectory inset and flight summary include every stage;
  - key-moment stills are distinct instants chosen by priority.
- `View3d`: extra gap between stages in exploded views, stage brackets with names and masses, and `stage` in the parts
  list.

### GIF colours (v0.16.2)

- Fix: animation GIFs showed wrong, flashing colours. The JDK's GIF writer stored its own default 216-colour table and
  remapped the indexed frames, so the palette in the file did not match the pixels. `report.Gif` now writes the file
  itself: GIF89a with our median-cut palette as the global colour table, and LZW-coded frames. A test decodes the GIF
  and compares its colours with the rendered frames. The PNG stills and the MP4 were never affected.

### Bug sweep and older designs (v0.16.3)

- A sweep ran every tool that needs no extra input on every bundled example through the real server. Fixes:
  - designs with catalogue parts (the "Deployable payload" example) failed to open: OpenRocket's core module does not
    bind the `ComponentPresetDao` its loader asks for, so `OrRuntime` binds it;
  - `add_avionics_bay` and `edit_components` could not use materials carried in the design's file (e.g. a
    manufacturer's kraft paper): material names now also match materials used in the design, and the bay copies the
    tube's material object;
  - `render_3d` on an empty design gave an internal error instead of saying there is nothing to draw, and stability
    showed "NaN cal".
- Older designs: RockSim `.rkt` files open as imports, never written back over the original. OpenRocket's conversion
  warnings are returned by `open_design`. Four more bundled examples are listed (3-D printed fins, base drag, two
  pod designs).
- Team standards: stiffness, strength and Poisson's ratio for balsa, basswood (*Wood Handbook*) and printed PLA, PETG
  and ABS, so flutter and fin FEA run on common student fin materials.
- `OrRuntime.motors()` no longer uses a deprecated OpenRocket call.

### Design library and rail buttons (v0.17.0)

- `design_library` (`or.Library`): scans a folder (recursively, up to 2000 design files) for .ork and .rkt designs and
  .csv altimeter logs. Designs are read with OpenRocket's loader without being registered, summarised, and cached by
  path, size and modification time.
  - The year comes from the last 19xx/20xx in the relative path, else the file date.
  - The predicted apogee comes from the saved results of the rocket's selected configuration, else a copy of its first
    simulation is flown (`simulate`, default true).
  - A log belongs to the design whose file name appears in the log's name. The rocket name comes second, since several
    files often share one. Ties go to the nearest folder. Without a name match, a log goes to the only design in its
    folder. CSVs that are not a climb above 10 m are listed as skipped.
  - Search by text, diameter (2% slack), impulse class or range, years, stages and "flew". `similarTo` ranks by a
    log-ratio distance on diameter (weighted 2x), launch mass and total impulse.
  - The trend gives the prediction error per flight, the mean by year, the overall bias and scatter, and a first-guess
    scale factor when the bias exceeds 5% over two or more flights.
- `rail_buttons` (`or.RailButtons`): OpenRocket 24.12 shortens the rail for launch lugs (effective launch rod length)
  but not for rail buttons, so its rail exit velocity counts rail the rocket never uses.
  - The tool runs the simulation and maps speed and time against travel along the rail (altitude / cos(launch angle)).
    It evaluates each layout:
    - guided travel = rail - standoff - (aft end - aft button);
    - the single-button time between the two buttons clearing the rail top;
    - tip-off rate and angle of a rigid rocket pivoting on the aft button under m g sin(theta) at the CG and
      q S CNα atan(w/v) at the CP;
    - slop = clearance / spacing;
    - button side loads as a couple.
  - Aft button: the aft end of the last main-stack body tube minus an edge margin (max(15 mm, 0.1 D)), over a backing
    ring within a calibre when there is one. An existing lower button is kept.
  - Forward button: 5 mm steps over the main-stack body tubes, minimising tip-off angle + slop. It snaps to a
    ring, bulkhead or coupler within half a calibre if that costs at most 10% more.
  - `apply` reuses the design's buttons: one component with two instances on a shared tube, else one per tube. With
    no buttons, it adds a Delrin pair. The result is verified against OpenRocket's instance positions, and undo
    reverts it.

### Bug and speed sweep (v0.17.1)

- A sweep ran about 45 tool calls on each of the 15 bundled examples, plus an edge-case probe (zero, negative, unknown
  and oversized inputs). Fixes:
  - **Arguments:** `McpServer` refuses arguments a tool does not declare, naming the tool's arguments and the closest
    match. Before, they were dropped silently: `search_motors` with a made-up filter returned every motor. Enum
    arguments are checked and normalised (case, spaces, hyphens). An unknown value used to surface as a Java
    `IllegalArgumentException` or a bare "Unknown action".
  - **Physical inputs:** `Args.positive` guards masses, rates, lengths and volumes that must be above zero
    (`size_parachute` returned an "Infinity" parachute for 0 ft/s; `ejection_charge` a 0 g charge for a 0-length
    bay). Budget lines with negative mass are refused. Negative `limit`, `maxCandidates` and `index` values no
    longer throw index errors.
  - **`sweep`:** an unknown launch-condition name is refused (it used to fly the same rocket N times).
  - **`rail_buttons`:** a standoff taller than the rail is refused with that reason.
  - **Component references** may be a piece of a name that only one part has. Ambiguous pieces list the candidates.
  - **`optimize_fins`** prefers the main airframe's fin set over fin sets on pods or side boosters, and names the
    candidates when it cannot choose.
  - **User files** (`TextFiles`): UTF-8 or Windows-1252, without a byte-order mark. Logs, budgets and aero tables
    exported from Windows software failed to read before.
  - **`design_library`:**
    - years are no longer taken from motor names ("M2020");
    - simulation exports are not counted as flights;
    - CSVs over 50 MB are skipped;
    - linked files are not followed;
    - designs are read in parallel on the simulation pool.
- Speed: `AscentOnly` ends a flight branch 0.5 s after its first recovery deployment past apogee (or 60 s after
  apogee). Studies that read only the climb use it: motor ranking, optimizers, shape study, ballast and roll. About
  half the integration steps are skipped. Ascent results are identical (`AscentOnlyTest`), and the benchmark went
  from 73 s to 64 s. Monte Carlo, `compare_flight` and anything reporting descent or landing still fly the whole
  flight.
- Repeatability: OpenRocket does not save a simulation's random seed in the .ork. Each open drew a new turbulence
  seed, so tilt, drift and landing points changed from session to session (the two-stage example's sustainer
  landing ranged over 32-44 m across three runs). `Designs.repeatable` gives every opened simulation the fixed seed
  that new simulations use, without marking the file changed; `RepeatableTest` reopens a file and compares.
- Server instructions mention `rail_buttons` and `design_library`.

### Electronics (v0.18.0)

- `or.SensorSim`: the flight resampled at a fixed rate into what each sensor measures.
  - **Specific force on x:** pad support up to liftoff; on the rail, thrust minus drag with the rail carrying g sin(angle)
    sideways. In flight, (T - D) / m.
  - **Specific force on y:** CN q A / m from OpenRocket's normal-force coefficient (zero after the first deployment).
  - **Gyro and air:** OpenRocket's roll, pitch and yaw rates; ambient pressure and temperature.
  - **GPS:** latitude and longitude (OpenRocket keeps them in degrees). No fix above 515 m/s or 18 km.
  - **Phases:** pad, rail, boost, coast, descent, landed.
  - **Measurement:** Gaussian noise, rounding to the resolution, clipping at the range (and at a barometer's min/max).
  - **Output:** the CSV, an events CSV, and `check`. The check splits accelerometer peaks into boost/coast and parachute
    openings (an upper bound, since OpenRocket opens canopies instantly), and finds the window above
    `baroUnreliableAboveMach`.
- `or.AltimeterSettings`:
  - **Channels:** per recovery device from its deployment configuration. Apogee devices get primary apogee + delay and
    backup + `backupDrogueDelay`; altitude devices get primary h and backup h - `backupMainOffset`. Ejection deployment
    gets a warning.
  - **Matching:** each channel is matched to its simulated opening by component id; events refer to the simulated copy.
  - **Mach lockout:** ceil(last time above the Mach limit before apogee + margin), with a warning when it comes within
    2 s of apogee.
  - **Static ports:** via `Avionics.staticPorts`, shared with `add_avionics_bay`.
  - **Card:** Markdown with a checklist.
- Standards: `electronics.sensors` (typical student parts) and `electronics.altimeter` (backup delay and offset, Mach
  limit, lockout margin).
- Tests: `SensorSimTest`, which checks the accelerometer against the trajectory and covers 1 g on the pad and under the
  canopy, noise sd, clipping, the CSV and events file, range failures and the transonic window; and
  `AltimeterSettingsTest`, which covers lockout, backups, static ports, the card, and two-stage branches.

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
