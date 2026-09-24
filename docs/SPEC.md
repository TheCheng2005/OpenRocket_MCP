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

### Phase 2 — next

- **Optimizer**: expose OpenRocket's built-in optimizer (`info.openrocket.core.optimization`) as a goal-seeking tool
  (e.g. reach 1.8 cal by changing fin span with minimum apogee loss; hit a target apogee with ballast).
- **Sections**: identify independently tethered sections from the design (separation points) for per-section landing
  energy, bay volumes and nose cone interior volume without manual input.
- **Monte Carlo / dispersion**: wind, launch angle and motor variation → landing ellipse and worst-case loads.
- **RASAero overrides**: import RASAero CP/CD tables as OpenRocket overrides (DTEG R10.3.1 for diameter changes).
- **Reports**: generate a calculation report (inputs, equations, values, sources) in the team's test-report format,
  plus the two stability-vs-time plots required by DTEG R10.3.2.
- More rule sets (Spaceport America Cup / IREC, NASA Student Launch) as JSON.

### Phase 3 — later

- Live control of the OpenRocket GUI via an OpenRocket plugin (instead of a fork).
- One-click Claude Desktop install (`.mcpb` bundle with a bundled Java runtime).
- Remote (HTTP) transport for claude.ai.

## Validation

- Calculators are unit-tested against published ISA values and against the worked numbers in the team's recovery
  documents (terminal velocities, opening forces, bay lengths, cord volumes, pin counts) and the 0.006·D²·L BP rule.
- End-to-end tests drive every tool through JSON-RPC on OpenRocket's two-stage example.
- Not yet validated against flight data — compare against altimeter logs after each flight and record calibration
  (e.g. measured packing factors, canopy fill constants) in the team standards.
