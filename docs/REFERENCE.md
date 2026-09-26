# OpenRocket MCP — technical reference

For teams and developers who want the details: every tool, the settings in the team standards file, the methods
behind the numbers and their limits, and how to build and test the server. The [README](../README.md) is the
plain-language overview.

## Tools

| Area | Tools |
|---|---|
| Designs | `open_design` (.ork, bundled examples, new), `get_design` (tree, motors, **stability of every stage stack**), `describe_component`, `edit_components`, `add_component`, `remove_component`, `set_deployment`, `set_stage_separation`, `flight_configuration`, `save_design` |
| Motors | `search_motors` (diameter, class, **cert level**, manufacturer), `rank_motors` (simulates candidates: target apogee / max apogee / smallest motor meeting rail-exit rules), `set_motor` (incl. air-start ignition), `import_motor_file` (.eng/.rse), `create_custom_motor` (**liquid / hybrid / static-fire curves, with tank CG shift**) |
| Flight | `run_simulation` (apogee, Mach, rail exit, TWR, min/max stability, per-stage events, deployments, landing distance), `get_flight_data` (down-sampled series, e.g. stability-vs-time plots), `sweep` (launch conditions or any component property) |
| Recovery chain | `recovery_analysis` (deployment airspeed/density/mass from the sim → opening load by Knacke Cx and finite-mass inflation → shear pins), `deployment_delay_sweep` (how late can the drogue fire?), `size_parachute` (+ real chutes from the parts database), `search_parachutes`, `opening_shock`, `shear_pins`, `ejection_charge` (black powder), `recovery_bay_fit` (bay volume from the design: tube or nose-cone interior), `descent_energy` |
| Goals & dispersion | `optimize` (goal-seek 1–3 properties: target apogee, max apogee, target stability, min mass — with stability / rail-exit / Mach / apogee constraints), `ballast` (how much nose weight for a stability target, refined by simulation), `monte_carlo` (randomized wind and launch angle, plus optional **structure mass, drag, thrust and parachute Cd uncertainty**: landing ellipse per stage, apogee spread, worst stability and rail exit, worst deployment airspeed and opening load, and **which inputs drive the spread**) |
| Aerodynamics & wind | `aero_analysis` (OpenRocket's aero model queried directly: CD split into friction / pressure / base, CP, CNα and static margin vs Mach, **drag per component**, OpenRocket's geometry warnings), `wind_profile` (**winds aloft**: OpenRocket's multi-level wind model from forecast / sounding levels or a power-law shear profile; every tool then flies it) |
| External data | `import_aero_table` (**RASAero II** aero export or any Mach/CD/CP CSV: drag replaces OpenRocket's in every simulation, power-on/off; CP used for a stability check against the simulated CG, as Launch Canada asks for diameter changes), `compare_flight` (**altimeter log vs simulation**: apogee, time to apogee, drogue and main descent rates, overlay plot, and the drag factor that reproduces your flight) |
| Parts & drawings | `search_parts` / `apply_preset` (OpenRocket's manufacturer parts database for body tubes, nose cones, couplers, rings, bulkheads, rail buttons, launch lugs, chutes), `draw_rocket` (side-profile SVG from OpenRocket's geometry with CG and CP; also in the report) |
| Design studies | `compare_shapes` (**nose cone and fin shape trade study**: every nose profile, optionally at several lengths, and every fin edge profile flown in OpenRocket; apogee, CD at the design Mach, stability, mass, and guidance), `recovery_sections` (**tethered sections from the design**: landing mass, velocity and kinetic energy per section, energy if the main fails, bay fill and a black powder estimate), `structural_loads` (**axial and bending loads at every joint** for boost and max q with a gust, inertial relief, wall stress and margin) |
| Launch day | `weather_forecast` (**site forecast by GPS coordinates** from Open-Meteo: ground wind, gusts, temperature, pressure and winds at pressure levels up to the jet stream; applied to the simulation as a wind profile with site altitude, temperature and pressure; pasted-JSON fallback; `wind_profile` for winds entered by hand), `flight_card` (one-page launch-day card: vehicle, CG/CP, motors with optimum and closest available ejection delay, predictions, recovery settings, sections and landing energy, drift per ground wind, rule check, sign-off) |
| Heating & roll | `aero_heating` (stagnation / recovery temperature at the nose tip, fin leading edges and body along the flight vs each material's service temperature, Sutton-Graves nose-tip heat flux and load), `roll_analysis` (fin cant / misalignment sweep: roll rate, roll at burnout, pitch-frequency crossing, alignment tolerance) |
| Reviews & files | `compare_designs` (**design diff** against another open design, another .ork or an earlier **git revision** of the same file: length, mass, CG, CP, stability, motors, apogee, velocity, Mach, rail exit, flight stability, descent rates, landing, every rule check whose status changed, and component edits matched by OpenRocket's persistent ids; both flown in the same conditions; optional Markdown summary; warns when an edit is hidden by a mass override), `list_files` (designs, motor files, tables and reports in the workspace) |
| Structures | `fin_flutter` (flutter speed of every fin set along the simulated flight — NACA TN 4197 with the corrected constant — worst margin, and the thickness or shear modulus that fixes it); also part of `check_requirements` |
| Reports | `generate_report` (Markdown design review with rule checks, stability by stage, a wind-sensitivity flight-card table, recovery chain, methods, plus the two stability-vs-time SVG plots DTEG R10.3.2 asks for and a CSV), `export_flight_data` (full-resolution CSV) |
| LC 2027 advanced | `pressure_vessel` (proof ≥ 1.5·MEOP, burst ≥ 2·MEOP·weld knockdown, COPV ≥ 4·MEOP, Barlow estimate), `advanced_probation` (probation level from GLPP volume, static-fire Isp requirement, AASI) |
| Rules & standards | `check_requirements` (LC 2027 edicts: L:D ≤ 45/25, damping ratio 0.03–0.5 / 0.05–0.3, static margin ≥ 10% of body length to 2× burn time, no metal rail buttons, SRAD Isp ≥ 100 s, dress-rehearsal pop tests, plus a manual checklist; and R4: launch angle, rail exit ≥ 100 ft/s, TWR by year and per stage, ≥ 1.5 cal ascent stability incl. 30 km/h wind, over-stability, air-start tilt & altitude inhibit, dual-event, drogue 50–150 ft/s, main ≤ 1500 ft & < 30 ft/s), `get_standards`, `update_standards`, `load_standards`, `set_units` |

Also: MCP **prompts** (`recovery_review`, `design_review`, `motor_selection`) and **resources** (`openrocket://standards`,
`openrocket://rules`, `openrocket://methods`).

What-if tools (`rank_motors`, `sweep`, `optimize`, `ballast`, `monte_carlo`, `deployment_delay_sweep`) run each variant on a
copy of the rocket, in parallel across CPU cores, and never modify the open design. Variants share the same wind
turbulence so differences come from the change being studied; Monte Carlo results are repeatable for a given seed.

Units are switchable (`metric`, `imperial`, `both`). Every input accepts units — `"20 ft/s"`, `"4.343 in"`, `"15 psi"`,
`"75 ft-lbf"`; bare numbers are SI.

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
- **Shape studies** rank options with OpenRocket's empirical drag model; differences under ~2% are within its
  uncertainty. **Sections** assume bays open at their forward end (name the joints otherwise). **Loads** are rigid-body
  quasi-static with inertial relief; they size couplers and fasteners but do not replace buckling checks or tests.
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

## Plots

`run_simulation` and `monte_carlo` take `plotPath` (SVG): the flight profile (altitude against time per stage, with
burnout, separation, apogee and deployments marked) and the landing map (every landing and the 2-sigma ellipse per
stage, equal-scale axes, the pad marked). `generate_report` includes the flight profile. Plots follow the team's unit
setting (imperial for an imperial team, else metric) and light / dark themes. The optimizer also reports the fin flutter
margin of each design and, with `meetRules` or `minFlutterMargin`, only accepts fins that meet it (materials of unknown
stiffness are not constrained).

## Development

```sh
./gradlew test                  # ~200 tests: calculators vs the team's worked examples and hand calculations, property
                                # tests (scaling laws, inverses), protocol, standards, SVG, OpenRocket-backed checks, end-to-end MCP
./gradlew installDist           # build/install/openrocket-mcp/bin/openrocket-mcp[.bat]
python3 scripts/benchmark.py    # scenario benchmark: realistic team requests, pass/fail + timings + output size
python3 scripts/make_examples.py  # rebuilds docs/EXAMPLES.md and its plots from real runs (also run in CI)
```

`scripts/benchmark.py` drives a fresh server over stdio through realistic requests (design a 10k ft rocket from
scratch and make it pass Launch Canada; size recovery and check loads; two-stage checks; a custom liquid engine;
dispersion and a design-review report; fin flutter, ballast and vehicle-uncertainty dispersion; aero analysis, winds
aloft, RASAero import and flight-log calibration; shape study, recovery sections and structural loads; launch-day weather and flight card; a design
review diff) and checks each answer against engineering expectations. It runs in CI.

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

