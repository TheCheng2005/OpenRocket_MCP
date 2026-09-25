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
- Loads at every airframe joint during boost and at maximum speed, to size couplers and fasteners.

**Competition and reviews**
- A full check against the Launch Canada rules, with the rule numbers and a checklist of what to verify by hand.
- A design-review package: report, stability plots and flight data, ready for your review board.
- Pressure vessel margins and probation level for liquid and hybrid programmes.
- Use RASAero data where Launch Canada asks for it.

**After you fly**
- Load your altimeter file: Claude compares it with the prediction and tunes the model for next time.

## Getting started

You need **Java 17 or newer** ([Temurin](https://adoptium.net)) on Windows, macOS or Linux.

```sh
git clone https://github.com/thecheng2005/openrocket_mcp.git
cd openrocket_mcp
./gradlew installDist        # Windows: gradlew.bat installDist
```

**Claude Code:** run `claude` inside the folder and it is ready. To use it from any folder:

```sh
claude mcp add openrocket -- /path/to/openrocket_mcp/scripts/openrocket-mcp
# Windows:
claude mcp add openrocket -- C:\path\to\openrocket_mcp\scripts\openrocket-mcp.cmd
```

**Claude Desktop:** Settings → Developer → Edit Config, and add:

```json
{
  "mcpServers": {
    "openrocket": { "command": "/path/to/openrocket_mcp/scripts/openrocket-mcp" }
  }
}
```

(On Windows use `C:\\path\\to\\openrocket_mcp\\scripts\\openrocket-mcp.cmd`.)

Then just ask — for example: *"Open the two-stage example and check it against Launch Canada rules."*

## Make it your team's

Copy [`openrocket-mcp.example.json`](openrocket-mcp.example.json) to `openrocket-mcp.json` and commit it, so everyone
on the team gets the same answers. It holds your units, launch site altitude, safety factors, shear pin ratings and fin
materials. You can also just tell Claude ("our pins are 2-56 nylon, 35 lbf each") and it will update the file.

## Good to know

- Every number is an engineering estimate from OpenRocket and standard calculations. Claude says where each number
  comes from. It does **not** replace ground tests, flight tests, your mentors or the range safety officer.
- Nothing is saved to your design file until you ask Claude to save it.
- Details on every tool, the methods behind them and their limits: [docs/REFERENCE.md](docs/REFERENCE.md).

## License

GPL-3.0-or-later, like OpenRocket. See [LICENSE](LICENSE).
