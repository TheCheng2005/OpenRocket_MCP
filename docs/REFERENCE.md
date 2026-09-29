# OpenRocket MCP — technical reference

For teams and developers who want the details: every tool, the settings in the team standards file, the methods
behind the numbers and their limits, and how to build and test the server. The [README](../README.md) is the
plain-language overview.

## Tools

The 67 tools, grouped by what a team is doing. Bold marks the main job of each tool.

### Session

- `design_status`: **where the design stands**. It gives the rule-check summary, then what is not set up yet: motor,
  deployment, electronics, RASAero data where the diameter changes, team standards and launch site. It also lists mass
  overrides and unsaved changes, and the next steps in order with the tool for each.
- `undo` / `redo` / `history`: every tool call that changed the rocket is one step, up to 50 steps. Motors, deployment
  and all component properties are included. Files are untouched until `save_design`.

### Designs

- `open_design`: opens a .ork file, a bundled example or a new design.
- `get_design`: the component tree, motors, and the **stability of every stage stack**.
- `describe_component`, `edit_components`, `add_component`, `remove_component`: read and change parts.
- `set_deployment`, `set_stage_separation`, `flight_configuration`: events and configurations.
- `save_design`: writes the .ork (and the aero table next to it, if one is imported).

### Parts and drawings

- `search_parts` / `apply_preset`: OpenRocket's manufacturer parts database for body tubes, nose cones, couplers, rings,
  bulkheads, rail buttons, launch lugs and chutes.
- `draw_rocket`: a **cut-away** SVG from OpenRocket's geometry. It draws the outside profile and fins over the internals,
  colour-coded by kind: parachutes and shock cords, altimeters, batteries, trackers, ejection charges, switches,
  ballast, motor, couplers, rings and bulkheads. It also marks separation points, CG and CP. The report includes it.

### Motors

- `search_motors`: filter by diameter, class, **certification level** and manufacturer.
- `rank_motors`: **simulates the candidates** for target apogee, maximum apogee, or the smallest motor that meets the
  rail-exit rules.
- `set_motor`: includes air-start ignition.
- `import_motor_file`: .eng or .rse files.
- `create_custom_motor`: **liquid, hybrid and static-fire thrust curves**, with the tank CG shift.

### Flight

- `run_simulation`: apogee, Mach, rail exit speed, thrust-to-weight, minimum and maximum stability, events per stage,
  deployments and landing distance.
- `get_flight_data`: down-sampled time series, e.g. for stability-against-time plots.
- `sweep`: vary launch conditions or any component property and fly each value.

### Aerodynamics and wind

- `aero_analysis`: OpenRocket's aerodynamic model queried directly. It gives CD split into friction, pressure and base,
  plus CP, CNα and static margin against Mach, the **drag of each component** and OpenRocket's geometry warnings.
- `wind_profile`: **winds aloft**. It sets OpenRocket's multi-level wind model from forecast or sounding levels, or from
  a power-law shear profile. Every tool then flies in it.

### Goals and dispersion

- `optimize`: goal-seek 1 to 3 properties. Objectives are target apogee, maximum apogee, target stability or minimum
  mass. Constraints are stability, rail exit, Mach and apogee. It reports the fin flutter margin of each design; with
  `meetRules` or `minFlutterMargin` it only accepts fins that meet it (materials of unknown stiffness are not
  constrained).
- `ballast`: how much nose weight a stability target needs, refined by simulation.
- `monte_carlo`: randomized wind and launch angle, with optional **structure mass, drag, thrust and parachute Cd
  uncertainty**. It returns:
  - the landing ellipse per stage and the apogee spread;
  - the worst stability and rail exit speed;
  - the worst deployment airspeed and opening load;
  - **which inputs drive the spread**.

  `kmlPath` writes a Google Earth file (see below).

### Fin design

- `optimize_fins`: the **whole trapezoidal planform at once**: root chord, taper, span and sweep, optionally with a list
  of stock thicknesses. It can aim for maximum apogee, minimum mass or a target apogee. Candidates must meet:
  - the stability floor and ceiling from the rules, also in the maximum wind;
  - the rail exit speed;
  - the flutter margin.

  Only buildable shapes are tried: a minimum tip chord, no tip trailing edge behind the root unless allowed, a capped
  sweep, and a root no longer than the tube. It draws the planform; `apply` keeps the result.

### Design studies

- `compare_shapes`: a **nose cone and fin shape trade study**. It flies every nose profile (optionally at several
  lengths) and every fin edge profile in OpenRocket. It reports apogee, CD at the design Mach, stability, mass and
  guidance.
- `recovery_sections`: **tethered sections from the design**. Per section it gives landing mass, velocity and kinetic
  energy, the energy if the main fails, the bay fill and a black powder estimate.
- `structural_loads`: **axial and bending loads at every joint**, for boost and for max q with a gust. It includes
  inertial relief, wall stress and margin. `csvPath` writes the load cases for tube and coupler FEA.

### Mass

- `mass_budget`: **mass budget and weigh-in tracking**.
  - **Input:** CSV text, a CSV file or a list of lines. Columns are part, mass (units in the cell or the header),
    optional CG (from the part's front or from the nose), status, qty, section and parent.
  - **Matching:** each line is matched to a component by id, name or a similar name.
  - **Comparison:** model against budget per line, largest differences first. Weighed sections are counted once, and
    model parts the budget leaves out are listed. It projects dry and launch mass, adds contingency on everything not
    weighed, and gives the margin to a target launch mass.
  - **`apply`:** writes mass and CG overrides into the design:
    - weighed sections become section overrides;
    - per-copy components are rescaled so the set weighs the number given;
    - several lines for one part are summed;
    - parts the design lacks are added as mass components in their parent.

    It warns when an enclosing section override hides a part, and reports stability before and after.
  - **No budget yet:** it writes the model's breakdown as a CSV template.

### Avionics

- `add_avionics_bay`: an **electronics bay** between two airframes. It adds:
  - a coupler with a switch band, bulkheads and a sled;
  - one altimeter per circuit, each with its own battery and switch;
  - a GPS tracker with its own battery, in the nose or the bay (it warns about RF-blocking airframes);
  - four ejection charges on the bulkheads, each facing the bay it opens;
  - U-bolts and static port sizing.

  It packs the shock cords and parachutes against the bay, and warns when they do not fit before the nose shoulder or
  motor mount. `check_requirements` then counts altimeters and batteries against the electronics edicts.

### Recovery chain

- `recovery_analysis`: deployment airspeed, air density and mass from the simulation, then the opening load (Knacke Cx
  and finite-mass inflation), then the shear pins.
- `deployment_delay_sweep`: how late can the drogue fire?
- `size_parachute`: the canopy size for a descent rate, plus real chutes from the parts database.
- `search_parachutes`, `opening_shock`, `shear_pins`, `ejection_charge` (black powder).
- `recovery_bay_fit`: bay volume from the design, in a tube or a nose-cone interior.
- `descent_energy`: landing energy per section.

### Structures

- `fin_flutter`: the flutter speed of every fin set along the simulated flight (NACA TN 4197 with the corrected
  constant). It gives the worst margin and the thickness or shear modulus that fixes it. `check_requirements` runs it too.
- `fin_fea`: a **finite-element check of a fin with CalculiX**.
  - **Model:** 8-node shells clamped at the root, with in-plane engineering constants from the team standards.
  - **Load:** the worst flight load (a gust at max q, or the largest simulated q × angle of attack) on the most loaded
    fin.
  - **Results:** tip deflection, stress margin with the load safety factor (the corner peak is reported apart), and
    natural frequencies labelled bending or torsion, with hand estimates alongside.

  The `.inp` deck is always written and also opens in Abaqus and PrePoMax. `plotPath` draws the fin coloured by stress.

### Heating and roll

- `aero_heating`: stagnation and recovery temperature at the nose tip, fin leading edges and body along the flight,
  against each material's service temperature, plus the Sutton-Graves nose-tip heat flux and heat load.
- `roll_analysis`: a fin cant / misalignment sweep. It gives the roll rate, the roll at burnout, the pitch-frequency
  crossing and the alignment tolerance.

### CFD, CAD and manufacturing

- `export_geometry` writes three things:
  - the **outer mold line as STL**, with a named region per part, the base and each fin set. The body is watertight and
    the fins are sunk into it. It works with OpenFOAM, SimScale, Fluent, STAR-CCM+ or CAD.
  - **fin flat patterns as DXF**, with tabs.
  - a **CFD run matrix** from the simulated flight. Each Mach number is paired with the altitude and air state where the
    flight reaches it, plus max q and max Mach, the Reynolds number, and OpenRocket's CD and CP for comparison. It comes
    with a results template that `import_aero_table` reads back.

  It also draws a shaded 3-D preview of the regions as SVG.

### External data

- `import_aero_table`: a **RASAero II** aero export, or any Mach/CD/CP CSV. Its drag replaces OpenRocket's in every
  simulation, power-on and power-off. Its CP is used for a stability check against the simulated CG, as Launch Canada
  asks for diameter changes.
- `compare_flight`: an **altimeter log against the simulation**. It compares apogee, time to apogee, and drogue and main
  descent rates, draws an overlay plot, and finds the drag factor that reproduces your flight.

### Launch day

- `weather_forecast`: a **site forecast by GPS coordinates** from Open-Meteo: ground wind, gusts, temperature, pressure
  and winds at pressure levels up to the jet stream. It is applied to the simulation as a wind profile, with the site's
  altitude, temperature and pressure. Without internet, paste the JSON, or enter winds by hand with `wind_profile`.
- `flight_card`: a one-page launch-day card. It covers the vehicle, CG/CP, motors with the optimum and closest
  available ejection delay, predictions, recovery settings, sections and landing energy, drift per ground wind, the
  rule check and sign-off lines.

### Reviews and files

- `compare_designs`: a **design diff** against another open design, another .ork, or an earlier **git revision** of the
  same file.
  - It compares length, mass, CG, CP, stability, motors, apogee, velocity, Mach, rail exit, flight stability, descent
    rates, landing, and every rule check whose status changed.
  - Component edits are matched by OpenRocket's persistent ids, and both designs fly in the same conditions.
  - It can write a Markdown summary, and warns when an edit is hidden by a mass override.
- `list_files`: designs, motor files, tables and reports in the workspace.

### Reports

- `generate_report`: a Markdown design review. It has the rule checks, stability by stage, a wind-sensitivity flight-card
  table, the recovery chain and the methods. It also writes the two stability-against-time SVG plots DTEG R10.3.2 asks
  for, and a CSV.
- `export_flight_data`: the full-resolution CSV.

### Rules and standards

- `check_requirements` checks two rule sets and adds a checklist of what to verify by hand.
  - **LC 2027 edicts:**
    - L:D ≤ 45 (25 recommended);
    - damping ratio 0.03–0.5 (0.05–0.3 recommended);
    - static margin ≥ 10% of body length up to 2× the burn time;
    - no metal rail buttons;
    - SRAD Isp ≥ 100 s;
    - dress-rehearsal pop tests.
  - **DTEG R4:**
    - launch angle;
    - rail exit ≥ 100 ft/s;
    - thrust-to-weight by year and per stage;
    - ≥ 1.5 cal ascent stability, including in 30 km/h wind;
    - over-stability;
    - air-start tilt and altitude inhibit;
    - dual-event recovery;
    - drogue descent 50–150 ft/s;
    - main deployment ≤ 1500 ft and main descent < 30 ft/s.
- `get_standards`, `update_standards`, `load_standards`, `set_units`: the team standards and units.

### LC 2027 advanced programmes

- `pressure_vessel`: proof ≥ 1.5·MEOP, burst ≥ 2·MEOP·weld knockdown, COPV ≥ 4·MEOP, and a Barlow estimate.
- `advanced_probation`: the probation level from the GLPP volume, the static-fire Isp requirement, and AASI.

### Common to all tools

- **Units:** switchable (`metric`, `imperial`, `both`). Every input accepts units, such as `"20 ft/s"`, `"4.343 in"`,
  `"15 psi"` or `"75 ft-lbf"`; bare numbers are SI.
- **What-if tools never change the design.** `rank_motors`, `sweep`, `optimize`, `ballast`, `monte_carlo` and
  `deployment_delay_sweep` fly each variant on a copy of the rocket, in parallel across CPU cores.
- **Fair comparisons:** variants share the same wind turbulence, so differences come from the change being studied.
  Monte Carlo results repeat for a given seed.
- **Prompts and resources:** MCP prompts `recovery_review`, `design_review` and `motor_selection`; resources
  `openrocket://standards`, `openrocket://rules` and `openrocket://methods`.

## Team standards

Copy [`openrocket-mcp.example.json`](../openrocket-mcp.example.json) to `openrocket-mcp.json` (in the directory the server
runs from, or point `OPENROCKET_MCP_STANDARDS` at it) and commit it, so everyone on the team gets the same answers:

- `units`, `ruleset` (`launch-canada-r4`, `none`, or a path to your own rules JSON), `competitionYear`
- `launchSite`: altitude above sea level (**set this** — deployment air density depends on it), lat/lon, rail length,
  launch angle, design wind
- `structures`: required flutter margin and fin-material shear moduli (typical G10, carbon, aluminum, plywood values
  built in; put your laminate's measured value here)
- `recovery`: Cx, opening-load method (`infinite_mass` / `finite_mass` / `max`), canopy fill constant, safety factors
  for shear pins and ejection force, backup-charge factor, packing factor and measured packing factors, shock cord
  cross-section, **shear pin ratings** (e.g. `"4-40 nylon": {"strength": "140 N"}`)

Claude can change them with `update_standards` (and save them with `saveTo`).

## Methods (and their limits)

See `openrocket://methods` for equations and sources. In short:

- **Flight**: OpenRocket 24.12 6-DOF simulation, Barrowman aerodynamics. For airframes with diameter changes,
  Launch Canada expects RASAero CP/CD overrides — the checker flags this.
- **Deployment conditions** come from the simulation (airspeed = Mach × speed of sound, so horizontal velocity and wind
  are included), not from vertical-only hand estimates.
- **Opening load**: Knacke (NWC TP 6575) infinite-mass `Cx·q·CdA`, plus a finite-mass inflation integration with fill time
  `n·D0/v`; Knacke's mass ratio indicates which applies. The design load is never below steady-descent drag.
- **Black powder**: ideal-gas method, R = 266 in·lbf/(lbm·°R), T = 3307 °R (≈ 0.006·D²·L g at 15 psi).
- **Fin flutter**: NACA TN 4197 screening estimate with K = 2.674 (the widely copied 1.337 form overestimates flutter
  speed by √2, corrected in Apogee Peak of Flight #615), evaluated at every point of the flight. Solid plate fins only;
  composites need an effective shear modulus, and a stiffness test or FEA before relying on it.
- **Fin FEA**: CalculiX S8R shells (8-node, reduced integration), root fully clamped, uniform pressure equal to the fin's
  normal force from OpenRocket's Barrowman fin model at the design angle of attack (the fins square to the flow carry it,
  2/n of the set). Checked against cantilever plate theory (tip deflection, root stress, first frequency within 1-3%).
  A homogeneous plate with in-plane engineering constants, not a ply-by-ply laminate model; the real pressure peaks near
  the leading edge, and root fillets or tabs make real fins more flexible than a clamp.
- **Fin optimization** flies every candidate with OpenRocket's own drag model even when an aero table is imported (the
  table describes the old fins); re-run CFD on the chosen shape.
- **CFD hand-off**: STL in the rocket frame (x from the nose tip, freestream in +x), reference area = maximum body
  cross-section, as OpenRocket and RASAero use. Flat fins with square edges; no rail buttons or fillets.
- **Drag breakdown**: OpenRocket reports drag per fin (and per rail button); `aero_analysis` counts every copy and lists
  the whole-rocket corrections (body friction form factor, fin thickness) that belong to no single part.
- **Shape studies** rank options with OpenRocket's empirical drag model; differences under ~2% are within its
  uncertainty.
- **Sections** assume bays open at their forward end (name the joints otherwise).
- **Loads** are rigid-body, quasi-static, with inertial relief. They size couplers and fasteners but do not replace
  buckling checks or tests.
- **Weather**: Open-Meteo forecast (set `OPENROCKET_MCP_WEATHER_URL` for a mirror); pressure-level winds placed at their
  geopotential heights above the site; turbulence from the gust factor. Forecasts carry uncertainty: re-check on the day.
- **Heating** temperatures are adiabatic-wall upper bounds (surfaces lag them); **roll** comes from OpenRocket's
  fin-cant model and ignores other asymmetries, so the alignment tolerance is a build target, not a margin.
- **Imported aero tables** apply until the first stage separation (a RASAero table describes one stack); the flight-log
  drag fit assumes the motor, mass and launch conditions of the simulation match the day.
- **Winds aloft**: with a multi-level profile, tools that set "the" wind (sweeps, the 30 km/h design-wind check, Monte
  Carlo) scale and rotate the whole profile from its lowest level, keeping its shape.
- **OpenRocket quirks handled**: OpenRocket 24.12's wind getters silently switch a simulation back to the single-wind
  model, and its first mass calculations after loading a design disagree by ~1 mm of CG; both are worked around (and
  covered by tests) so profiles persist and static margins are stable.
- **Mass overrides**: if a section's mass is overridden for its subcomponents (a weighed section), OpenRocket ignores
  mass added inside it. The tools warn about this, and `ballast` adds its mass to the override.
- **Cd reference area**: OpenRocket uses the nominal canopy area. Vendor Cd values quoted on projected area (e.g. 2.2)
  must be paired with projected area.

Outputs are engineering estimates for design iteration. They do not replace ground tests, flight tests, mentors or the
RSO.

## Plots, images, progress and cancellation

- **Plots:** `run_simulation` and `monte_carlo` take `plotPath` (SVG).
  - The flight profile shows altitude against time per stage, with burnout, separation, apogee and deployments marked.
  - The landing map shows every landing and the 2-sigma ellipse per stage, on equal-scale axes with the pad marked.

  `generate_report` includes the flight profile. Plots follow the team's unit setting (imperial for an imperial team,
  else metric) and light or dark themes.
- **Images in the chat:** tools that draw also return the picture as a PNG in the result, so clients that show images
  (Claude Desktop, claude.ai) display it in the chat. These are `draw_rocket` and `export_geometry`, plus
  `run_simulation`, `monte_carlo`, `compare_flight`, `optimize_fins` and `fin_fea` when given a plot path. Set
  `OPENROCKET_MCP_INLINE_IMAGES=0` to send text only.
- **Progress:** long tools send MCP progress notifications (simulations flown out of the total) when the client passes a
  `progressToken`. Over stdio they are notifications; on the team server they are server-sent events.
- **Cancel:** `notifications/cancelled` stops a call at its next batch of simulations, and no result is sent for it.
- **Ordering:** over stdio, requests are handled one at a time in the order they arrive; `ping` and cancellation are
  answered straight away. On the team server, calls on the same design take turns and different designs run in
  parallel.
- **Google Earth:** `monte_carlo` `kmlPath` writes the pad, every landing and each stage's 2-sigma ellipse, placed at
  the team's launch site (`launchSite.latitude` / `longitude`) or at `siteLatitude` / `siteLongitude`.

## Development

```sh
./gradlew test                  # unit and integration tests (see below)
./gradlew installDist           # build/install/openrocket-mcp/bin/openrocket-mcp[.bat]
python3 scripts/benchmark.py    # scenario benchmark: realistic team requests, pass/fail + timings + output size
python3 scripts/make_examples.py  # rebuilds docs/EXAMPLES.md, its plots and the demo designs from real runs (also in CI)
```

`./gradlew test` runs about 175 tests: calculators against the team's worked examples and hand calculations,
property tests (scaling laws, inverses), the protocol, standards, SVG output, OpenRocket-backed checks and end-to-end
MCP calls.

`scripts/benchmark.py` drives a fresh server over stdio through realistic team requests and checks each answer against
engineering expectations. It runs in CI. The scenarios:

- design a 10,000 ft rocket from scratch and make it pass Launch Canada;
- size recovery and check the loads; two-stage checks; a custom liquid engine, pressure vessels and probation;
- dispersion and a design-review report; fin flutter, ballast and vehicle-uncertainty dispersion;
- aero analysis, winds aloft, RASAero import and flight-log calibration;
- shape study, recovery sections and structural loads; fin optimization, CFD export and fin FEA;
- launch-day weather and flight card; a design-review diff;
- an avionics bay; pictures in the chat, progress and cancel, status, undo and redo, the mass budget and the KML map.

The server speaks MCP over stdio (JSON-RPC 2.0, newline-delimited); stdout is reserved for the protocol, logs go to
stderr. See [`SPEC.md`](SPEC.md) for the design and roadmap.

## Team server (HTTP)

`openrocket-mcp --http` serves MCP over HTTP (Streamable HTTP transport, JSON responses, batches accepted, no
server-initiated stream so `GET /mcp` is 405) for claude.ai custom connectors, Claude Desktop and Claude Code.

| Option | Default | |
|---|---|---|
| `--port` | 8765 (`$PORT`) | |
| `--host` | 127.0.0.1 | `0.0.0.0` to accept other machines |
| `--workspace` | current folder | shared folder; every tool path is resolved inside it and cannot leave it (`..`, absolute paths and symbolic links out are refused) |
| `--token` | `$OPENROCKET_MCP_TOKEN`, else generated once into `WORKSPACE/.openrocket-mcp-token` | at least 16 characters; required unless `--no-auth` on a loopback address |
| `--public-url` | `http://HOST:PORT` | the address people use, for the links the server prints and `list_files` returns |
| `--allow-origin` | none | extra browser origins; others are refused (DNS-rebinding protection); requests without `Origin` (server-to-server) are allowed |

Endpoints: `POST /mcp` with `Authorization: Bearer TOKEN`, or `POST /mcp/TOKEN` for clients that only take a URL;
`GET /files/TOKEN/` lists the workspace with download links and a drag-and-drop upload (`PUT /files/TOKEN/path`,
200 MB limit, dot-files hidden and refused); `GET /health`. Tool calls on the same design run one at a time; calls on
different designs run in parallel. Paths in results are shown relative to the workspace. Open designs, units and
standards are shared by everyone connected. `Dockerfile` builds a container that serves `/workspace` (git included,
so `compare_designs` can read earlier revisions of a checked-out repository).

## Claude Desktop extension

`./gradlew mcpb` builds `build/distributions/openrocket-mcp-VERSION-PLATFORM-ARCH.mcpb`: the manifest (generated from
the registered tools by `io.github.openrocketmcp.Manifest`), the server and OpenRocket jars, and a `jlink` Java runtime
for the build machine's platform (~80 MB). Claude Desktop asks for the rocket folder (`OPENROCKET_MCP_WORKSPACE`:
relative paths, reports and `openrocket-mcp.json` live there) and optionally a standards file. CI builds and
smoke-tests it (`scripts/test_mcpb.py` unpacks it and starts it exactly as the manifest says) on Linux, Windows and
macOS arm64 / x64, and attaches the bundles to GitHub releases for `v*` tags.

## Claude integration

- MCP prompts: `recovery_review`, `design_review`, `motor_selection`; resources: `openrocket://standards`,
  `openrocket://rules`, `openrocket://methods`.
- Claude Code skill: `.claude/skills/rocket-design-review` (the review workflow); the same skill for Codex and other
  agents in `.agents/skills/rocket-design-review` (kept identical by `SkillsTest`).
- Codex: stdio (`command`) or the team server (`url` + `bearer_token_env_var`) in `~/.codex/config.toml`; raise
  `tool_timeout_sec` (default 60 s) for dispersion and motor ranking. Codex offers the tools to its model under the
  `mcp__openrocket` namespace with the schemas unchanged. `scripts/test_codex.py` runs the real Codex CLI against the
  server with a local stand-in for the model (in CI, Codex version pinned).
- Team standards file location: `openrocket-mcp.json` in the workspace (the directory the server runs from, or
  `OPENROCKET_MCP_WORKSPACE` / `--workspace`), or the path in the `OPENROCKET_MCP_STANDARDS` environment variable.
- Imported aero tables are kept next to the design as `NAME.aero.json` (SI, readable, diffable) when it is saved, and
  loaded again with it.

## Prior art

[Poyraxx/openrocket-mcp-support](https://github.com/Poyraxx/openrocket-mcp-support) (an OpenRocket fork with an MCP
bridge), [openrocket/orhelper](https://github.com/openrocket/orhelper).

