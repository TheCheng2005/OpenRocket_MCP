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
| Design | Rocket design | Parts list on the left, the rocket in the middle, the selected part's properties on the right (no pop-up dialog). The add-part buttons are below the view; the ones that can't go where you are pointing are greyed out. Summary shows stability against your rule set's minimum, mass, length and a quick flight of the active configuration. Fins show their outline (freeform fins are drawn and edited there); parts with a catalogue have a Catalogue button; every part can be given a paint colour. Picture makes an exploded, cut-away or outside 3-D image with a numbered parts list, or the side drawing with CG and CP. The Avionics bay button lays out a whole electronics bay between two tubes. |
| Motors | Motors & Configuration | Motors, recovery and stages as three tables on one page, a row per flight configuration. The motor list is OpenRocket's own database (the thrustcurve.org motors that come with OpenRocket 24.12, offline): it can be searched, filtered to motors that fit the mount, and shows the thrust curve. Import file adds a .eng, .rse or zipped motor file; Your own motor makes one from static-fire data (a thrust curve to paste or load from CSV, or an average thrust and burn time) with its masses, delays and CG. Both are kept in the designs folder's `motors/` and load whenever the app starts, so a design keeps its motor. |
| Flights | Flight simulations | The simulations, the launch conditions of the selected one, and its plot. Video turns the selected flight into an animation (a chase camera, the separations and canopies, the numbers on screen): a GIF, an MP4 when ffmpeg is installed, and a sheet of key-moment stills. |
| Standards | (not in OpenRocket) | The team's standards, which every check uses: the competition rule set, the launch site, recovery and structures safety factors, shear pin ratings, material data, altimeter, battery and radio settings. Only what the team changes goes into its file (`openrocket-mcp.json`); a dot marks those values, and each can go back to its default. |
| Targets | (not in OpenRocket) | The team's targets: a minimum, goal and maximum for apogee, speeds, rail exit, stability, flutter margin, mass, descent rates, landing and more, each with the margin to the nearer limit (met, close within 5%, missed), checked again after each edit. The competition rules sit beside them. |
| Flight data | (not in OpenRocket) | After a flight: load the altimeter log and see it against the simulation flown in the day's conditions, with the drag factor that matches the measured apogee. Read a folder of past rockets and logs to see how well apogee was predicted over the years, and open or compare any of them. |
| Analysis | (not in OpenRocket) | 50 analyses from the engine, by area: rule check, readiness, checking meeting notes against the design, Launch Canada probation level; stability and drag vs Mach, ballast, shape trades; recovery loads, parachute sizing, late deployment, shear pins, ejection charge, opening shock, bay fit, parachute catalogue, landing energy; fin flutter, fin stress (FEA with CalculiX), airframe loads, heating, material trade, pressure vessel; avionics bay, launch-day forecast, rail buttons, flight card, altimeter settings, sensors, simulated sensor data for flight software, radio link, power budget, winds aloft; Monte Carlo, motor ranking, parameter sweep, fin planform optimizer, optimizer; part from a shop link, compare designs, progress log, edit history, RASAero table import, flight log against the simulation; report, CSV and geometry export. Pick one, fill in what it asks for (simulation, parts and other designs from lists, files with Browse, lists of items as small tables), run. Results show as tables with pass, warn and fail in colour, plots, and a Show button for files it writes. |

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

Pictures, drawings and videos go to the designs folder's `exports/`, and show in the app as soon as they are made. None
of them needs an assistant.

![Picture: exploded view with parts list](rocketmaxxing/picture.png)

![Flight video](rocketmaxxing/video.png)

![Your own motor](rocketmaxxing/own-motor.png)

![Team standards](rocketmaxxing/standards.png)

Plots in Analysis: stability and Cd against Mach, the parameter sweep, late deployment, the parachute size chart,
the Monte Carlo landing map and the flight log against the simulation.

![Stability and drag vs Mach](rocketmaxxing/aero-plot.png)

![Flight log against the simulation](rocketmaxxing/flight-log.png)

Analysis results are kept while the app is open and dimmed when the design has changed since they ran. Results
that are text from the engine follow the metric or imperial setting, but not the per-quantity units.

The view has three modes. Side is OpenRocket's figure, with the airframe see-through and CG and CP marked. Back looks
along the rocket from behind. 3D can be turned, and Cut-away opens the airframe. Click a part to select it.

Settings (the gear, top right) has the units, metric or imperial and per quantity, and light or dark theme.

### Problems

Every error and warning says whose problem it is:

| Kind | Means | Where it shows |
|---|---|---|
| Input | Something in what was asked can't be used: a value, a unit, a name. | The pop-up, the field in the Analysis form (marked red), the result. |
| Physics | The rocket or its flight: it doesn't lift off, tumbles, never reaches apogee; OpenRocket's own warnings (large angle of attack, a gap in the airframe, fins too thick for its model). | Under the Design summary after every edit, the result, the Flights table (a warning sign on the run), the rule check. |
| Output | A result to treat with care: a value that could not be computed, or one from beyond where the model is valid (past Mach 3; OpenRocket's body calculations past Mach 1). | Under the Design summary, the result, the rule check ("Within the models' range"). |
| System | Something on this computer or in RocketMaxxing failed, not the design: no internet, a file that can't be written, a program not installed, or a bug. | The pop-up and the result. |

The warning sign at the top right opens the Problems list: what to watch in the current design's flight, then
every error since the app started, newest first, with where it happened. It is red when there are errors you haven't
looked at, amber when the flight has something to watch. A bug in RocketMaxxing (in the engine or in the window)
comes with an error ID and a report: Copy report, Save report, or Report on GitHub, which opens a new issue with the
report filled in for you to read and send. The report has the versions, what was asked and where it failed, but not
the design. The engine's log has the same error ID next to the full trace.

![Problems](rocketmaxxing/problems.png)

![Analysis: rule check](rocketmaxxing/analysis.png)

![Recovery bay fit, with its list of items](rocketmaxxing/bay-fit.png)

![Checking what the meeting notes say against the design](rocketmaxxing/claims.png)

![Motors](rocketmaxxing/motors.png)

![Flights](rocketmaxxing/flights.png)

Edits can be undone (up to 50). Nothing is written to the file until you save.

While the engine works (starting up, a long analysis, a video), a small rocket flies past whatever happens to be up
there (geese, an airliner, a kite, the Moon, a satellite, a UFO...) and says what it is up to.

![Loading](rocketmaxxing/loader.gif)

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

RocketMaxxing runs on macOS (Apple Silicon and Intel), Windows and Linux. Installers with Java included are built per
platform: `./gradlew rocketmaxxingInstaller` makes a `.msi` on Windows (needs WiX 3), a `.dmg` on macOS and a `.deb` on
Linux (needs `fakeroot`) in `build/installers/`; `-PinstallerType=app-image` makes a folder that runs without
installing. The *RocketMaxxing installers* workflow builds all four (two for the Mac) on every push to `main` and to
the working branches, after starting each packaged app and using it; the installers are the run's artifacts.

### On a Mac

1. On GitHub, open **Actions**, then **RocketMaxxing installers**, then the latest green run, and download
   `rocketmaxxing-macos-apple-silicon-dmg` (M1 and later; Apple menu > About This Mac says "Apple M...") or
   `rocketmaxxing-macos-intel-dmg`. Unzip it to get `RocketMaxxing-0.1.0.dmg`.
2. Open the `.dmg` and drag RocketMaxxing into Applications.
3. The app is not signed with an Apple developer certificate yet, so the first time macOS refuses to open it. Open it
   once, then in **System Settings > Privacy & Security** click **Open Anyway** next to "RocketMaxxing was blocked",
   and confirm. Or, in Terminal: `xattr -dr com.apple.quarantine /Applications/RocketMaxxing.app`. After that it opens
   like any other app.
4. It opens in Chrome or Edge in app mode if one is installed, otherwise in Safari. Designs go in `~/RocketMaxxing`.

Optional extras are found where Homebrew, MacPorts or conda put them, even though an app started from the Dock does
not see the Terminal's PATH: `brew install ffmpeg` for MP4 videos, CalculiX (`conda install -c conda-forge calculix`)
for the fin FEA, and Claude Code or Codex for the assistant.

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

Every engine analysis is now on the Analysis page. Two need something outside the app: the fin FEA runs CalculiX
when it is installed (otherwise it writes the model to run elsewhere), and the launch-day forecast needs internet
access to Open-Meteo (offline, paste Open-Meteo's JSON into the form). A product link works offline the same way:
paste the page's text.
