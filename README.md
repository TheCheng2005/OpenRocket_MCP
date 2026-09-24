# OpenRocket MCP

An [MCP](https://modelcontextprotocol.io) server that lets Claude design, simulate and check rockets with
[OpenRocket](https://openrocket.info)'s physics — built for student competition teams and amateur rocketeers.

You state goals ("land under 20 ft/s", "10,000 ft on an L2 motor", "1.5–2 cal all the way up"), and Claude uses
OpenRocket simulations plus recovery-engineering calculators to get there, checking the result against the
**Launch Canada DTEG R4** rules (or your own rule set) and your team's standards.

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
| Recovery chain | `recovery_analysis` (deployment airspeed/density/mass from the sim → opening load by Knacke Cx and finite-mass inflation → shear pins), `deployment_delay_sweep` (how late can the drogue fire?), `size_parachute` (+ real chutes from the parts database), `search_parachutes`, `opening_shock`, `shear_pins`, `ejection_charge` (black powder), `recovery_bay_fit`, `descent_energy` |
| Rules & standards | `check_requirements` (Launch Canada R4: launch angle, rail exit ≥ 100 ft/s, TWR by year and per stage, ≥ 1.5 cal ascent stability incl. 30 km/h wind, over-stability, air-start tilt & altitude inhibit, dual-event, drogue 50–150 ft/s, main ≤ 1500 ft & < 30 ft/s), `get_standards`, `update_standards`, `load_standards`, `set_units` |

Also: MCP **prompts** (`recovery_review`, `design_review`, `motor_selection`) and **resources** (`openrocket://standards`,
`openrocket://rules`, `openrocket://methods`).

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
- **Cd reference area**: OpenRocket uses the nominal canopy area. Vendor Cd values quoted on projected area (e.g. 2.2)
  must be paired with projected area.

Outputs are engineering estimates for design iteration. They do not replace ground tests, flight tests, mentors or the
RSO.

## Development

```sh
./gradlew test          # unit tests (calculators checked against the team's worked examples) + end-to-end MCP tests
./gradlew installDist   # build/install/openrocket-mcp/bin/openrocket-mcp[.bat]
```

The server speaks MCP over stdio (JSON-RPC 2.0, newline-delimited); stdout is reserved for the protocol, logs go to
stderr. See [`docs/SPEC.md`](docs/SPEC.md) for the design and roadmap.

## License

GPL-3.0-or-later (OpenRocket is GPL-3.0). See [LICENSE](LICENSE). Prior art:
[Poyraxx/openrocket-mcp-support](https://github.com/Poyraxx/openrocket-mcp-support) (an OpenRocket fork with an MCP
bridge), [openrocket/orhelper](https://github.com/openrocket/orhelper).
