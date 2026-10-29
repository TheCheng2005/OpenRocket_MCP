# RocketMaxxing

RocketMaxxing is a desktop app for designing and simulating rockets. It runs offline and needs no AI subscription.
Under the hood it is the same engine as the OpenRocket MCP server (OpenRocket 24.12 plus this project's tools), and it
reads and writes ordinary OpenRocket `.ork` files, so a design can go back and forth between the two programs.

Version 0.1.0 covers OpenRocket's three main tabs, and an Analysis page that runs the engine's engineering tools.
See [the plan](APP_PLAN.md) for what comes next.

![Design page](rocketmaxxing/design.png)

## Pages

| RocketMaxxing | OpenRocket | Notes |
|---|---|---|
| Design | Rocket design | Parts list on the left, the rocket in the middle, the selected part's properties on the right (no pop-up dialog). The add-part buttons are below the view; the ones that can't go where you are pointing are greyed out. Summary shows stability against your rule set's minimum, mass, length and a quick flight of the active configuration. |
| Motors | Motors & Configuration | Motors, recovery and stages as three tables on one page, a row per flight configuration. The motor list can be searched, filtered to motors that fit the mount, and shows the thrust curve. |
| Flights | Flight simulations | The simulations, the launch conditions of the selected one, and its plot. |
| Analysis | (not in OpenRocket) | 42 analyses from the engine, by area: rule check and readiness; stability and drag vs Mach, ballast, shape trades; recovery loads, parachute sizing, late deployment, shear pins, ejection charge, opening shock, bay fit, landing energy; fin flutter, airframe loads, heating, material trade, pressure vessel; rail buttons, flight card, altimeter settings, sensors, radio link, power budget, winds aloft; Monte Carlo, motor ranking, parameter sweep, optimizer; compare designs, progress log, edit history, RASAero table import, flight log against the simulation; report, CSV and geometry export. Pick one, fill in what it asks for (simulation, parts and other designs from lists, files with Browse, lists of items as small tables), run. Results show as tables with pass, warn and fail in colour, plots, and a Show button for files it writes. |

Every panel and section folds (click its title). The Parts and Properties panels fold to a strip at the side to give
the view more room. What is folded is remembered.

The Design page has a Rule check panel: the competition rule check runs again a moment after each edit, and its title
shows the fail, warn and pass counts even when it is folded. It can be turned off in Settings.

![Rule check on the Design page](rocketmaxxing/rule-check.png)

Plots in Analysis: stability and Cd against Mach, the parameter sweep, late deployment, the parachute size chart,
the Monte Carlo landing map and the flight log against the simulation.

![Stability and drag vs Mach](rocketmaxxing/aero-plot.png)

![Flight log against the simulation](rocketmaxxing/flight-log.png)

Analysis results are kept while the app is open and dimmed when the design has changed since they ran. Results
that are text from the engine follow the metric or imperial setting, but not the per-quantity units.

The view has three modes. Side is OpenRocket's figure, with the airframe see-through and CG and CP marked. Back looks
along the rocket from behind. 3D can be turned, and Cut-away opens the airframe. Click a part to select it.

Settings (the gear, top right) has the units, metric or imperial and per quantity, and light or dark theme.

![Analysis: rule check](rocketmaxxing/analysis.png)

![Recovery bay fit, with its list of items](rocketmaxxing/bay-fit.png)

![Motors](rocketmaxxing/motors.png)

![Flights](rocketmaxxing/flights.png)

Edits can be undone (up to 50). Nothing is written to the file until you save.

| Keys | |
|---|---|
| Ctrl+Z, Ctrl+Y or Ctrl+Shift+Z | Undo, redo |
| Ctrl+S, Ctrl+Shift+S | Save, save as |
| Ctrl+O | Open |
| Delete | Delete the selected part |

Sizes can be typed with a unit, e.g. `3 in` in a millimetre field.

## Install and run

Java 17 or newer. Building the interface the first time also needs Node.js 20 or newer.

```bash
git clone https://github.com/TheCheng2005/OpenRocket_MCP.git
cd OpenRocket_MCP
./gradlew rocketmaxxing
build/install/openrocket-mcp/bin/rocketmaxxing        # Windows: build\install\openrocket-mcp\bin\rocketmaxxing.bat
```

The app opens in its own window (Chrome or Edge in app mode if installed, otherwise your default browser). The file
dialogs start in `~/RocketMaxxing`. The app quits two minutes after its window is closed.

| Option | |
|---|---|
| `--workspace DIR` | Designs folder (default `~/RocketMaxxing`) |
| `--port N` | Local port (default: any free one) |
| `--no-browser` | Print the address instead of opening a window |
| `--dev URL` | Also accept calls from a development server |

## How it works

```
 window ──POST /rpc (token)──► local server, 127.0.0.1 only ──► the engine's tools ──► OpenRocket 24.12
```

- The launcher starts the engine on `127.0.0.1` with a new random token and opens the window with that token, so other
  programs and web pages on the computer can't use it.
- The window calls the engine's ordinary tools (`open_design`, `add_component`, `set_motor`, `undo`, `save_design`, ...)
  and a few app-only ones (`app_*`) that return plain SI numbers. The MCP server doesn't register the `app_*` tools.
- Edits go through the same property code as the chat tool `edit_components`.

Code:
- `src/main/java/io/github/openrocketmcp/app/`: launcher, local server, app tools, part editors.
- `app/`: the interface (Svelte 5, TypeScript, three.js, uPlot).

## Development

```bash
./gradlew installDist
build/install/openrocket-mcp/bin/rocketmaxxing --port 47801 --dev http://localhost:5173 --no-browser
cd app && npm install && npm run dev
```

Open the address the engine prints (`http://localhost:5173/#token=...`). `npm run check` type-checks the interface.
`AppToolsTest` covers the app tools and the local server, and `scripts/test_rocketmaxxing.py` starts the installed app
and uses it.

## Not in RocketMaxxing yet

Use OpenRocket on the same file for:
- editing freeform fin points, and the parts catalogue (the engine has it: `search_parts`, `apply_preset`);
- appearance and decals, photo studio, fin templates, printing;
- custom plot setups and exporting flight data (the engine has `export_flight_data`);
- the optimizer and component analysis (the engine has `optimize`, `sweep`, `aero_analysis`).

There are no installers with Java included yet; the app runs from the build folder.
