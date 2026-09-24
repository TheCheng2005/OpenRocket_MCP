---
name: rocket-design-review
description: Review a competition rocket (.ork) with the openrocket MCP server - stability, motor choice, recovery loads, shear pins, ejection charges and Launch Canada rule checks. Use when the user asks to check, size, or review a rocket design, parachute, motor, shock force, shear pins or black powder charge.
---

# Rocket design review with openrocket-mcp

Use the `openrocket` MCP tools; do not estimate numbers by hand when a tool computes them.

1. `get_standards` - note units, launch site, safety factors and rule set. If `launchSite.altitudeMsl` is null, ask for it
   (air density at deployment depends on it) and set it with `update_standards`.
2. `open_design` then `get_design` - components (ids), motors per configuration, stability of every stage stack.
3. `check_requirements` - explain every FAIL and WARN with the rule reference and a concrete fix.
4. Goals:
   - Descent rate -> `size_parachute` (with `device` to use the simulated descent mass), then `edit_components`.
   - Motor -> `rank_motors` (objective target_apogee / max_apogee / min_impulse_meeting_rules), then `set_motor`.
     Custom engines (liquid/hybrid/static-fire data) -> `create_custom_motor` or `import_motor_file`.
   - Stability (CP/CG) -> `optimize` a fin dimension or ballast `componentMass` (objective target_stability, or
     max_apogee with minStability), check the result, then apply. Use `sweep` to show the trade-off table.
   - Target apogee -> `optimize` with objective target_apogee (ballast, or motor choice via `rank_motors`).
   - Wind / landing area -> `monte_carlo` for the landing ellipse and worst-case deployment loads.
5. Recovery chain -> `recovery_analysis` (pinType from standards), `deployment_delay_sweep` for late drogue deployment,
   `ejection_charge` (pins to break), `recovery_bay_fit`, `descent_energy` per tethered section.
6. Re-run `check_requirements`. Only `save_design` after the user agrees; prefer saving to a new file.
7. For design reviews, `generate_report` writes report.md, the DTEG R10.3.2 stability plots and a CSV.

Write results like the team's test reports: inputs, equations, values, results. Label each number as simulated
(OpenRocket), calculated (tool formula) or assumed (standards). Remind the user that ground tests and RSO review are
still required.
