package io.github.openrocketmcp;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.or.OrRuntime;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.AdvancedTools;
import io.github.openrocketmcp.tools.AeroTools;
import io.github.openrocketmcp.tools.AnalysisTools;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.tools.DesignTools;
import io.github.openrocketmcp.tools.MotorTools;
import io.github.openrocketmcp.tools.RecoveryTools;
import io.github.openrocketmcp.tools.ReportTools;
import io.github.openrocketmcp.tools.SimTools;
import io.github.openrocketmcp.tools.StandardsTools;
import io.github.openrocketmcp.tools.StudyTools;
import io.github.openrocketmcp.tools.LaunchTools;
import io.github.openrocketmcp.tools.StructureTools;

/** Entry point: stdio MCP server for OpenRocket. */
public final class Main {
	public static final String VERSION = "0.9.0";

	static final String INSTRUCTIONS = """
			OpenRocket MCP: design, simulate and check high-power / competition rockets with OpenRocket's physics.
			Workflow: open_design -> get_design -> run_simulation / check_requirements -> change things -> re-check -> save_design.
			- Let the tools do numeric work: use size_parachute, rank_motors, sweep, optimize, ballast, fin_flutter, monte_carlo,
			  recovery_analysis, deployment_delay_sweep, aero_analysis rather than estimating by hand.
			- Launch day: ask for the site's GPS coordinates and use weather_forecast (or wind_profile with winds the user
			  types), then flight_card.
			- Use OpenRocket's data: search_parts / apply_preset for real catalog parts, wind_profile for winds aloft,
			  draw_rocket to show the design. What-if tools never modify the design. Deployment airspeed comes from the simulation and includes horizontal velocity and wind.
			- Inputs accept units ("20 ft/s", "4 in", "15 psi"); bare numbers are SI. Output uses the team's unit setting.
			- Team standards (safety factors, pin ratings, launch site) and the competition rule set (default Launch Canada
			  2027: DTEG R4 + 2027 edicts) drive checks; see get_standards. Ask the team to set launchSite.altitudeMsl.
			- generate_report writes a design-review package (Markdown, stability plots, CSV).
			- Edits are in memory until save_design. Propose design changes and confirm before saving over the team's file.
			- Numbers are estimates (OpenRocket Barrowman aerodynamics, ideal-gas BP, Knacke opening loads). Say which values are
			  simulated vs. calculated vs. rules of thumb. They do not replace ground tests, RSO review or mentors.""";

	private Main() {
	}

	public static McpServer build(Context ctx) {
		McpServer server = new McpServer("openrocket-mcp", VERSION, INSTRUCTIONS);
		DesignTools.register(server, ctx);
		MotorTools.register(server, ctx);
		SimTools.register(server, ctx);
		RecoveryTools.register(server, ctx);
		AnalysisTools.register(server, ctx);
		ReportTools.register(server, ctx);
		StructureTools.register(server, ctx);
		AeroTools.register(server, ctx);
		StudyTools.register(server, ctx);
		LaunchTools.register(server, ctx);
		AdvancedTools.register(server, ctx);
		StandardsTools.register(server, ctx);
		Prompts.register(server, ctx);
		return server;
	}

	public static void main(String[] args) throws Exception {
		// stdout is the protocol channel: keep a handle to it and send everything else to stderr.
		PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
		System.setOut(System.err);
		if (List.of(args).contains("--version")) {
			protocol.println("openrocket-mcp " + VERSION);
			protocol.flush();
			return;
		}
		Context ctx = new Context(Standards.discover());
		McpServer server = build(ctx);
		// Load OpenRocket's motor and parts databases in the background so the first tool call is fast.
		Thread warm = new Thread(() -> {
			try {
				OrRuntime.motors();
				OrRuntime.presets();
				Log.info("OpenRocket databases loaded");
			} catch (RuntimeException e) {
				Log.error("OpenRocket initialization failed", e);
			}
		}, "openrocket-init");
		warm.setDaemon(true);
		warm.start();
		Log.info("openrocket-mcp " + VERSION + " ready; standards: "
				+ (ctx.standards().source() == null ? "built-in defaults" : ctx.standards().source()));
		server.serve(System.in, protocol);
	}
}
