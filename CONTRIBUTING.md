# Contributing

Thanks for helping. Rocketry teams rely on these numbers, so the bar is simple: **every change is tested, and every
physics change is checked against an independent reference.**

## Ways to help without writing code

- **Report a result that looks wrong** ([template](https://github.com/TheCheng2005/OpenRocket_MCP/issues/new?template=wrong-result.yml)):
  a number that disagrees with your hand calculation, RASAero, a ground test or a flight log. These are the most
  valuable reports we get.
- **Send flight data.** An altimeter log plus the `.ork` you flew lets us measure how close the predictions are. Remove
  anything private first.
- **Add or check a rule set.** Rule sets are JSON in `src/main/resources/openrocketmcp/rules/`; see
  `launch-canada-2027.json`. Every limit carries its rule reference.
- **Share team standards** (pin strengths from your shear tests, measured fin material stiffness, packing factors)
  with their source.

## Building and testing

You need Java 17 or newer.

```sh
./gradlew test                      # unit and end-to-end tests (~2 min)
./gradlew installDist               # build/install/openrocket-mcp/bin/openrocket-mcp
python3 scripts/benchmark.py        # realistic team requests, checked against engineering expectations
python3 scripts/make_examples.py    # rebuild docs/EXAMPLES.md and its plots
python3 scripts/test_codex.py       # the real Codex CLI against the server (needs `npm i -g @openai/codex`)
./gradlew mcpb && python3 scripts/test_mcpb.py   # Claude Desktop extension for your computer, started like Claude Desktop does
```

CI runs the tests on Linux, Windows and macOS, and the benchmark, examples, Codex check and extension builds as well.

## How the code is organised

| Folder | What lives there |
|---|---|
| `mcp/` | The MCP protocol (stdio and HTTP), argument parsing, schemas |
| `tools/` | One file per area; each tool is a `ToolDef` with a description written for the model |
| `or/` | Everything that talks to OpenRocket: simulations, analyses, optimizer, dispersion |
| `calc/` | Pure calculations (recovery, charges, flutter, atmosphere) with no OpenRocket dependency |
| `report/` | Reports, flight card, SVG plots and drawings |
| `standards/` | Team standards and rule sets |
| `units/` | Unit parsing and display (inputs accept "4 in", output follows the team setting) |

## Adding or changing a tool

1. Write the tool in the matching `tools/*Tools.java`. The description tells the model **when** to use it and **what** it
   returns; inputs accept units (`Schema.qty`) and outputs use `Units.fmt`.
2. Keep what-if tools read-only: work on copies (`Variants.of`) and leave the design alone unless the user asks to apply.
3. Test it: calculations in `src/test/java/.../calc`, OpenRocket-backed behaviour in `.../or`, and a scenario in
   `scripts/benchmark.py` if a team would use it in a workflow.
4. Document it: a row in `docs/REFERENCE.md`, and a line in the README's "What you can ask" if users will notice it.
   The Claude Desktop manifest picks up new tools automatically.

## Physics and engineering changes

- Cite the method (paper, report, standard) in the code comment and in `docs/REFERENCE.md` under Methods.
- Add a test against something independent: a worked example from the reference, a hand calculation, a limiting case,
  a scaling law, or an equilibrium / conservation check (see `PhysicsInvariantsTest` and `FlutterTest`).
- Say what the method does **not** cover; the tools report their assumptions to the user.

## Style

Match the surrounding code: tabs, short methods, comments that explain *why*. User-facing text is plain language, the
way you would explain it to a new team member.

## The skill files

The design-review skill ships twice: `.claude/skills/rocket-design-review` (Claude) and `.agents/skills/rocket-design-review`
(Codex and other agents). Edit the first and copy it to the second; `SkillsTest` checks they match.

## Security

Please report security problems (for example with the team server's token or file sandbox) privately through
[GitHub security advisories](https://github.com/TheCheng2005/OpenRocket_MCP/security/advisories/new), not in a public issue.

## License

By contributing you agree that your contribution is licensed under the GPL-3.0-or-later, like the rest of the project
and OpenRocket.
