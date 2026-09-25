# OpenRocket MCP

An [MCP](https://modelcontextprotocol.io) server that lets Claude design, simulate and check rockets with
[OpenRocket](https://openrocket.info)'s physics — built for student competition teams and amateur rocketeers.

You state goals ("land under 20 ft/s", "10,000 ft on an L2 motor", "1.5–2 cal all the way up"), and Claude uses
OpenRocket simulations plus recovery-engineering calculators to get there, checking the result against the
**Launch Canada 2027** rules (DTEG R4 + the LC 2027 edicts; R4 alone and your own rule sets are also supported) and
your team's standards.

```
You:    Our sustainer main needs to land under 20 ft/s. What chute, and will four 4-40 pins hold when the drogue opens?
Claude: size_parachute -> 36 in Rocketman DG-03 (Cd 0.85): 19.9 ft/s at the simulated 2.84 lb descent mass
        recovery_analysis -> drogue opens at 15 ft/s airspeed (sim, incl. horizontal velocity): design load 12.6 N ...
        deployment_delay_sweep -> joint holds for deployment delays up to 4 s ...
```

## What it does

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

## Install

Requires **Java 17+** ([Temurin](https://adoptium.net)). Works on Windows, macOS and Linux.

```sh
git clone https://github.com/thecheng2005/openrocket_mcp.git
cd openrocket_mcp
./gradlew installDist        # Windows: gradlew.bat installDist
```

### Claude Code

The repo ships a project `.mcp.json`, so running `claude` in the repo picks the server up (macOS/Linux). To use it from
any project:

```sh
claude mcp add openrocket -- /path/to/openrocket_mcp/scripts/openrocket-mcp
# Windows:
claude mcp add openrocket -- C:\path\to\openrocket_mcp\scripts\openrocket-mcp.cmd
```

The repo also includes a Claude Code skill (`.claude/skills/rocket-design-review`) with the review workflow.

### Claude Desktop

Add to `claude_desktop_config.json` (Settings → Developer → Edit Config):

```json
{
  "mcpServers": {
    "openrocket": {
      "command": "/path/to/openrocket_mcp/scripts/openrocket-mcp",
      "env": { "OPENROCKET_MCP_STANDARDS": "/path/to/your-team/openrocket-mcp.json" }
    }
  }
}
```

On Windows use `C:\\path\\to\\openrocket_mcp\\scripts\\openrocket-mcp.cmd`.

## Team standards

Copy [`openrocket-mcp.example.json`](openrocket-mcp.example.json) to `openrocket-mcp.json` (in the directory the server
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

## Development

```sh
./gradlew test                  # ~170 tests: calculators vs the team's worked examples and hand calculations, property
                                # tests (scaling laws, inverses), protocol, standards, SVG, OpenRocket-backed checks, end-to-end MCP
./gradlew installDist           # build/install/openrocket-mcp/bin/openrocket-mcp[.bat]
python3 scripts/benchmark.py    # scenario benchmark: realistic team requests, pass/fail + timings + output size
```

`scripts/benchmark.py` drives a fresh server over stdio through realistic requests (design a 10k ft rocket from
scratch and make it pass Launch Canada; size recovery and check loads; two-stage checks; a custom liquid engine;
dispersion and a design-review report; fin flutter, ballast and vehicle-uncertainty dispersion) and checks each answer
against engineering expectations (51 checks). It runs in CI.

The server speaks MCP over stdio (JSON-RPC 2.0, newline-delimited); stdout is reserved for the protocol, logs go to
stderr. See [`docs/SPEC.md`](docs/SPEC.md) for the design and roadmap.

## License

GPL-3.0-or-later (OpenRocket is GPL-3.0). See [LICENSE](LICENSE). Prior art:
[Poyraxx/openrocket-mcp-support](https://github.com/Poyraxx/openrocket-mcp-support) (an OpenRocket fork with an MCP
bridge), [openrocket/orhelper](https://github.com/openrocket/orhelper).
