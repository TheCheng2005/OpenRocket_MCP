# OpenRocket MCP

Talk to Claude about your rocket, and it answers with real [OpenRocket](https://openrocket.info) simulations.

Built for student competition teams and amateur rocketeers, and set up for **Launch Canada 2027** out of the box. Ask
in plain words, in feet or metres; Claude runs the numbers, checks them against the competition rules and your team's
standards, and explains what it found.

```
You:    Our main needs to land under 20 ft/s. Which chute, and will four 4-40 shear pins hold when the drogue opens?
Claude: A 36 in chute lands the 2.84 lb section at 19.9 ft/s. The drogue opens at 15 ft/s with a 12.6 N load,
        so four pins hold with plenty of margin — even if the drogue fires up to 4 s late.
```

## What you can ask

**Design and stability**
- Build a rocket from scratch, or open your team's `.ork` file and change it.
- "Is it stable all the way up, including in 30 km/h wind?" — checked at every moment of the flight, for every stage.
- "How much nose weight do I need?" or "How big should the fins be?"
- "Which nose cone and fin shape should we build?" — every common shape flown and compared.
- Pick real parts from OpenRocket's catalogue (tubes, nose cones, couplers, rail buttons, parachutes).
- Get a drawing of the rocket with its CG and CP marked.

**Motors**
- "Find a motor for 10,000 ft on an L2 certification" — candidates are flown, not just filtered.
- Use your own liquid or hybrid engine, or data from a static fire.

**Flight**
- Apogee, speed, Mach number, rail exit speed, thrust-to-weight, staging and landing distance.
- "Where will it land?" — up to a thousand possible flights with varied wind, launch angle, mass, drag and thrust.
- Use a wind forecast with winds at altitude, not just the ground wind.
- "Where does our drag come from?" — drag of every part, and how it changes with speed.

**Recovery**
- Choose parachutes for a target descent rate.
- Opening shock loads, shear pin count, black powder charge size, and whether it all fits in the bay.
- "How late can the drogue fire before the pins break?"
- Landing energy of every piece of the rocket, and what happens if the main fails.

**Structures**
- "Will our fins flutter?" — and how thick they need to be if they will.
- "Will it get too hot?" — nose tip, fin edges and body temperatures at speed against your materials' limits.
- "How straight do the fins need to be?" — roll rate from misaligned fins, and whether it risks a roll-pitch resonance.
- Loads at every airframe joint during boost and at maximum speed, to size couplers and fasteners.

**Competition and reviews**
- A full check against the Launch Canada rules, with the rule numbers and a checklist of what to verify by hand.
- A design-review package: report, stability plots and flight data, ready for your review board.
- "What changed since our last review?" — compare two versions of the design (another file, or an earlier commit)
  side by side: mass, stability, apogee, rule checks and every part that was added, removed or edited.
- Pressure vessel margins and probation level for liquid and hybrid programmes.
- Use RASAero data where Launch Canada asks for it. It is saved with your design.

**Launch day**
- "What will the winds be at our site on Saturday at 10 am?" — give the launch site's GPS coordinates and Claude pulls
  the forecast, including winds up at altitude, and flies the rocket in it. No internet? Just tell Claude the winds.
- A one-page flight card: predictions, motor delay, deployment settings, drift in each wind, and sign-off lines.

**After you fly**
- Load your altimeter file: Claude compares it with the prediction and tunes the model for next time.

## Getting started

Works with **Claude Desktop**, **claude.ai**, **Claude Code** and **Codex**.

**Claude Desktop (easiest).** Download the extension for your computer from the
[Releases page](https://github.com/TheCheng2005/OpenRocket_MCP/releases) — `win32` for Windows, `darwin-arm64` for
Apple-silicon Macs, `darwin-x64` for Intel Macs, `linux` for Linux — and double-click it. Claude Desktop asks for your
rocket folder (where your `.ork` files live) and you are done. Nothing else to install: Java and OpenRocket come
inside it. (If your team runs a team server, Claude Desktop can use that instead: see below.)

**The whole team, from claude.ai or Claude Desktop.** One person runs the team server on a computer or cloud machine everyone can reach:

```sh
docker build -t openrocket-mcp . && docker run -p 8765:8765 -v "$PWD/rockets:/workspace" openrocket-mcp
# or, with Java 17+:  ./gradlew installDist && build/install/openrocket-mcp/bin/openrocket-mcp --http --host 0.0.0.0
```

It prints a private link. Put it in claude.ai under Settings → Connectors → Add custom connector (the same connector
then shows up in Claude Desktop), and everyone on the team can use it. The server also prints a files page where people drop their `.ork` files and download reports.
claude.ai needs an `https://` address: host it somewhere with a certificate, or put a tunnel such as
`cloudflared tunnel --url http://localhost:8765` in front of it. Keep the link private — anyone with it can use the
server.

**Claude Code, or building it yourself.** You need **Java 17 or newer** ([Temurin](https://adoptium.net)).

```sh
git clone https://github.com/thecheng2005/openrocket_mcp.git
cd openrocket_mcp
./gradlew installDist        # Windows: gradlew.bat installDist
```

Run `claude` inside the folder and it is ready. To use it from any folder:

```sh
claude mcp add openrocket -- /path/to/openrocket_mcp/scripts/openrocket-mcp
# Windows:
claude mcp add openrocket -- C:\path\to\openrocket_mcp\scripts\openrocket-mcp.cmd
# Or connect to your team server:
claude mcp add --transport http openrocket https://your-server/mcp/YOUR-TOKEN
```

`./gradlew mcpb` builds the Claude Desktop extension for your own computer.

**Codex (CLI, IDE extension or app).** Build it as above, then add this to `~/.codex/config.toml`:

```toml
[mcp_servers.openrocket]
command = "/path/to/openrocket_mcp/scripts/openrocket-mcp"   # Windows: 'C:\path\to\openrocket_mcp\scripts\openrocket-mcp.cmd'
startup_timeout_sec = 60
tool_timeout_sec = 300        # dispersion and motor ranking can take a few minutes

[mcp_servers.openrocket.env]
OPENROCKET_MCP_WORKSPACE = "/path/to/your/rockets"          # optional: the folder with your .ork files
```

Or connect Codex to your team server:

```toml
[mcp_servers.openrocket]
url = "https://your-server/mcp"
bearer_token_env_var = "OPENROCKET_MCP_TOKEN"   # set this environment variable to the server's token
tool_timeout_sec = 300
```

For Claude's design-review routine in Codex too, copy [`.agents/skills/rocket-design-review`](.agents/skills/rocket-design-review)
into `~/.codex/skills/` (or into `.agents/skills/` in your team's repository).

Then just ask — for example: *"Open the two-stage example and check it against Launch Canada rules."*

## Make it your team's

Copy [`openrocket-mcp.example.json`](openrocket-mcp.example.json) to `openrocket-mcp.json` and commit it, so everyone
on the team gets the same answers. It holds your units, launch site altitude, safety factors, shear pin ratings and fin
materials. You can also just tell Claude ("our pins are 2-56 nylon, 35 lbf each") and it will update the file.

## Good to know

- Every number is an engineering estimate from OpenRocket and standard calculations. Claude says where each number
  comes from. It does **not** replace ground tests, flight tests, your mentors or the range safety officer.
- Nothing is saved to your design file until you ask Claude to save it.
- On a team server, everyone connected shares the open designs, so say which one you mean if several are open.
- Details on every tool, the methods behind them and their limits: [docs/REFERENCE.md](docs/REFERENCE.md).

## License

GPL-3.0-or-later, like OpenRocket. See [LICENSE](LICENSE).
