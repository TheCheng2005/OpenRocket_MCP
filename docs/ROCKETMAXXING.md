# RocketMaxxing

RocketMaxxing is a desktop app for designing and simulating rockets. It runs offline and needs no AI subscription.
Under the hood it is the same engine as the OpenRocket MCP server (OpenRocket 24.12 plus this project's tools), and it
reads and writes ordinary OpenRocket `.ork` files, so a design can go back and forth between the two programs.

Version 0.1.0 covers OpenRocket's three main tabs, and adds the engine's engineering tools, the team's targets with
live margins, flight data after a launch, and an optional assistant. See [the plan](APP_PLAN.md) for what comes next.

![Design page](rocketmaxxing/design.png)

## Pages

| RocketMaxxing | OpenRocket | Notes |
|---|---|---|
| Design | Rocket design | Parts list on the left, the rocket in the middle, the selected part's properties on the right (no pop-up dialog). The add-part buttons are below the view; the ones that can't go where you are pointing are greyed out. Summary shows stability against your rule set's minimum, mass, length and a quick flight of the active configuration. Fins show their outline (freeform fins are drawn and edited there); parts with a catalogue have a Catalogue button; every part can be given a paint colour. |
| Motors | Motors & Configuration | Motors, recovery and stages as three tables on one page, a row per flight configuration. The motor list can be searched, filtered to motors that fit the mount, and shows the thrust curve. |
| Flights | Flight simulations | The simulations, the launch conditions of the selected one, and its plot. |
| Targets | (not in OpenRocket) | The team's targets: a minimum, goal and maximum for apogee, speeds, rail exit, stability, flutter margin, mass, descent rates, landing and more, each with the margin to the nearer limit (met, close within 5%, missed), checked again after each edit. The competition rules sit beside them. |
| Flight data | (not in OpenRocket) | After a flight: load the altimeter log and see it against the simulation flown in the day's conditions, with the drag factor that matches the measured apogee. Read a folder of past rockets and logs to see how well apogee was predicted over the years, and open or compare any of them. |
| Analysis | (not in OpenRocket) | 42 analyses from the engine, by area: rule check and readiness; stability and drag vs Mach, ballast, shape trades; recovery loads, parachute sizing, late deployment, shear pins, ejection charge, opening shock, bay fit, landing energy; fin flutter, airframe loads, heating, material trade, pressure vessel; rail buttons, flight card, altimeter settings, sensors, radio link, power budget, winds aloft; Monte Carlo, motor ranking, parameter sweep, optimizer; compare designs, progress log, edit history, RASAero table import, flight log against the simulation; report, CSV and geometry export. Pick one, fill in what it asks for (simulation, parts and other designs from lists, files with Browse, lists of items as small tables), run. Results show as tables with pass, warn and fail in colour, plots, and a Show button for files it writes. |

Every panel and section folds (click its title). The Parts and Properties panels fold to a strip at the side to give
the view more room. What is folded is remembered.

The Design page has a Rule check panel: the competition rule check runs again a moment after each edit, and its title
shows the fail, warn and pass counts even when it is folded. It can be turned off in Settings.

![Rule check on the Design page](rocketmaxxing/rule-check.png)

![Targets with live margins](rocketmaxxing/targets.png)

![Flight data: a log against the simulation, and past rockets](rocketmaxxing/flight-data.png)

Freeform fins are edited in the Properties panel: drag a point, double-click an edge to add one, select a point and
press Delete to remove it, or type the coordinates. Trapezoidal and elliptical fins can be converted to freeform. Parts
that come in catalogues (tubes, nose cones, couplers, rings, rail buttons, parachutes...) open OpenRocket's parts
database, nearest the part's diameter first.

![Freeform fin editor](rocketmaxxing/fin-editor.png)

![Parts catalogue](rocketmaxxing/catalogue.png)

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

## The assistant (optional)

Settings > AI assistant puts an assistant beside the design. It works through the same tools as the app (and as the
MCP server), so what it changes shows at once and undoes like any other edit; each tool it used can be opened to see
what it sent and got back. It is off until you turn it on, and connects one of three ways:

| Connect with | What you need | Billing |
|---|---|---|
| **Claude subscription** | [Claude Code](https://claude.com/claude-code) installed and signed in (run `claude` once in a terminal and log in with your Claude Pro or Max account) | your plan's usage limits |
| **ChatGPT subscription** | [Codex](https://github.com/openai/codex) installed (`npm i -g @openai/codex`) and signed in (`codex login`) | your plan's usage limits |
| **Anthropic API key** | a key from the Anthropic Console, pasted in Settings (or `ANTHROPIC_API_KEY`) | per use, on your API account |

For Claude Code and Codex, RocketMaxxing runs the tool you already have on each message, headless, and lends it the
app's tools through a private MCP endpoint on `127.0.0.1` with a new random token. Claude Code runs without its own
tools (no shell, files or web) and Codex in its read-only sandbox, so they work only through RocketMaxxing's tools. The
sign-in stays with Claude Code or Codex; RocketMaxxing never sees it. Settings shows whether each is installed and
signed in (from `claude auth status` and `codex login status`) and lets you pick the model: Claude Code's aliases
(opus, sonnet, haiku) or its default, Codex's default or a model you name. A second message continues the same Claude
Code session or Codex thread. The app looks for the tools on PATH and where their installers put them; set
`ROCKETMAXXING_CLAUDE` or `ROCKETMAXXING_CODEX` to point at a particular one.

With an API key, the model can be Claude Opus 5.5 (the default), or Claude Sonnet 5.5 or Haiku 5.5 for lower cost, and
the key is kept in `~/.rocketmaxxing/assistant.json` (readable only by you on macOS and Linux), never in the designs
folder.

Whichever way, your messages, the open design's data and the tools' results go to Anthropic (Claude Code, API key) or
OpenAI (Codex).

![Connecting the assistant](rocketmaxxing/connect.png)

![The assistant](rocketmaxxing/assistant.png)

| Keys | |
|---|---|
| Ctrl+Z, Ctrl+Y or Ctrl+Shift+Z | Undo, redo |
| Ctrl+S, Ctrl+Shift+S | Save, save as |
| Ctrl+O | Open |
| Delete | Delete the selected part |

Sizes can be typed with a unit, e.g. `3 in` in a millimetre field.

## Install and run

Installers with Java included are built per platform: `./gradlew rocketmaxxingInstaller` makes a `.msi` on Windows
(needs WiX 3), a `.dmg` on macOS and a `.deb` on Linux (needs `fakeroot`) in `build/installers/`;
`-PinstallerType=app-image` makes a folder that runs without installing. The *RocketMaxxing installers* workflow builds
all three.

To run from source: Java 17 or newer. Building the interface the first time also needs Node.js 20 or newer.

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
- `src/main/java/io/github/openrocketmcp/app/`: launcher, local server, app tools, part editors, the assistant.
- `app/`: the interface (Svelte 5, TypeScript, three.js, uPlot).

## Development

```bash
./gradlew installDist
build/install/openrocket-mcp/bin/rocketmaxxing --port 47801 --dev http://localhost:5173 --no-browser
cd app && npm install && npm run dev
```

Open the address the engine prints (`http://localhost:5173/#token=...`). `npm run check` type-checks the interface.
`AppToolsTest` covers the app tools and the local server, `AssistantTest` the assistant against a stand-in for the API,
`AgentCliTest` reading Claude Code's and Codex's output, `scripts/test_app_agents.py` the assistant through the real
Claude Code and Codex with stand-in models, and `scripts/test_rocketmaxxing.py` starts the installed app
and uses it.

## Not in RocketMaxxing yet

Use OpenRocket on the same file for:
- decals and textures, the photo studio, fin templates, printing;
- custom plot setups (the Analysis page exports flight data);
- importing a fin outline from an image or a CSV file.
