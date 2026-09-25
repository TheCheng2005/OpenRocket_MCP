package io.github.openrocketmcp;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import io.github.openrocketmcp.mcp.HttpTransport;
import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.or.OrRuntime;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.AdvancedTools;
import io.github.openrocketmcp.tools.AeroTools;
import io.github.openrocketmcp.tools.AnalysisTools;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.tools.DesignLocks;
import io.github.openrocketmcp.tools.DesignTools;
import io.github.openrocketmcp.tools.FileTools;
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
	public static final String VERSION = "0.10.0";

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

	static final String TEAM = """

			- Team server: files live in a shared workspace. Use list_files to find designs and give paths relative to it
			  (e.g. "designs/rocket.ork"). People upload designs and download reports on the workspace page (list_files
			  gives the link). Open designs and unit settings are shared by everyone connected.""";

	public static McpServer build(Context ctx) {
		McpServer server = new McpServer("openrocket-mcp", VERSION, ctx.sandboxed() ? INSTRUCTIONS + TEAM : INSTRUCTIONS);
		DesignTools.register(server, ctx);
		FileTools.register(server, ctx);
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

	static final String USAGE = """
			Usage: openrocket-mcp [--version]                   MCP server over stdio (Claude Desktop, Claude Code)
			       openrocket-mcp --http [options]              shared team server over HTTP
			  --port N            port (default 8765, or $PORT)
			  --host ADDRESS      interface to listen on (default 127.0.0.1; 0.0.0.0 for every interface)
			  --workspace DIR     shared folder for designs and reports (default: the current folder)
			  --token SECRET      access token (default: $OPENROCKET_MCP_TOKEN, else generated once and kept in
			                      DIR/.openrocket-mcp-token)
			  --public-url URL    address people reach the server at, e.g. https://rockets.example.org
			  --allow-origin URL  extra browser origin allowed to call the server (repeatable)
			  --no-auth           no token (only with a 127.0.0.1 / localhost address)
			""";

	public static void main(String[] args) throws Exception {
		// stdout is the protocol channel: keep a handle to it and send everything else to stderr.
		PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
		System.setOut(System.err);
		List<String> a = List.of(args);
		if (a.contains("--version")) {
			protocol.println("openrocket-mcp " + VERSION);
			protocol.flush();
			return;
		}
		if (a.contains("--help") || a.contains("-h")) {
			protocol.print(USAGE);
			protocol.flush();
			return;
		}
		if (a.contains("--http")) {
			HttpTransport t;
			try {
				t = http(a);
			} catch (IllegalArgumentException | java.net.BindException e) {
				System.err.println("openrocket-mcp: " + e.getMessage());
				System.exit(2);
				return;
			}
			Runtime.getRuntime().addShutdownHook(new Thread(t::stop));
			new java.util.concurrent.CountDownLatch(1).await(); // serve until the process is stopped
			return;
		}
		// Claude Desktop extensions start the server in an arbitrary folder: OPENROCKET_MCP_WORKSPACE is the rocket folder.
		String wsEnv = System.getenv("OPENROCKET_MCP_WORKSPACE");
		Path ws = wsEnv == null || wsEnv.isBlank() ? Path.of("") : Path.of(wsEnv);
		Context ctx = new Context(Standards.discover(ws), ws, false);
		McpServer server = build(ctx);
		warmUp();
		Log.info("openrocket-mcp " + VERSION + " ready; standards: "
				+ (ctx.standards().source() == null ? "built-in defaults" : ctx.standards().source()));
		server.serve(System.in, protocol);
	}

	/** Loads OpenRocket's motor and parts databases in the background so the first tool call is fast. */
	static void warmUp() {
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
	}

	static String opt(List<String> a, String name, String fallback) {
		int i = a.indexOf(name);
		if (i < 0) {
			return fallback;
		}
		if (i + 1 >= a.size() || a.get(i + 1).startsWith("--")) {
			throw new IllegalArgumentException(name + " needs a value.\n" + USAGE);
		}
		return a.get(i + 1);
	}

	/** Token: --token, $OPENROCKET_MCP_TOKEN, else one generated on first start and kept in the workspace. */
	static String token(List<String> a, Path workspace) throws java.io.IOException {
		String t = opt(a, "--token", System.getenv("OPENROCKET_MCP_TOKEN"));
		if (t != null && !t.isBlank()) {
			if (t.length() < 16) {
				throw new IllegalArgumentException("Use a token of at least 16 characters.");
			}
			return t.trim();
		}
		Path f = workspace.resolve(".openrocket-mcp-token");
		if (Files.exists(f)) {
			return Files.readString(f).trim();
		}
		byte[] b = new byte[24];
		new SecureRandom().nextBytes(b);
		t = HexFormat.of().formatHex(b);
		Files.writeString(f, t + "\n");
		try {
			Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException ignored) {
			// not a POSIX file system
		}
		return t;
	}

	/** Shows workspace files as relative paths ("designs/rocket.ork"), as people know them from the files page. */
	static java.util.function.UnaryOperator<String> relativePaths(Path workspace) {
		if (workspace.getParent() == null) {
			return java.util.function.UnaryOperator.identity(); // the file system root: nothing sensible to strip
		}
		return relativePaths(workspace.toString(), workspace.getFileSystem().getSeparator());
	}

	static java.util.function.UnaryOperator<String> relativePaths(String workspace, String sep) {
		String raw = workspace + sep;
		String json = raw.replace("\\", "\\\\");
		if (!sep.equals("\\")) {
			return text -> text.replace(raw, "");
		}
		// Windows: in JSON output each backslash is doubled; show the rest of the path with "/" like the files page.
		java.util.regex.Pattern jsonPath = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(json) + "([^\"\\s]*)");
		java.util.regex.Pattern rawPath = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(raw) + "([^\"\\s]*)");
		return text -> {
			String t = jsonPath.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1).replace("\\\\", "/")));
			return rawPath.matcher(t).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1).replace("\\", "/")));
		};
	}

	static HttpTransport http(List<String> a) throws Exception {
		Path workspace = Path.of(opt(a, "--workspace", ".")).toAbsolutePath().normalize();
		Files.createDirectories(workspace);
		String host = opt(a, "--host", "127.0.0.1");
		int port = Integer.parseInt(opt(a, "--port", System.getenv().getOrDefault("PORT", "8765")));
		String token = a.contains("--no-auth") ? null : token(a, workspace);
		List<String> origins = new ArrayList<>();
		for (int i = 0; i < a.size() - 1; i++) {
			if (a.get(i).equals("--allow-origin")) {
				origins.add(a.get(i + 1));
			}
		}
		Standards.sandbox(workspace);
		Context ctx = new Context(Standards.discover(workspace), workspace, true);
		McpServer server = build(ctx);
		server.guard(new DesignLocks(ctx));
		server.outputFilter(relativePaths(workspace));
		HttpTransport t = new HttpTransport(server, workspace, token, origins);
		InetSocketAddress bound = t.start(host, port, Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
		String base = opt(a, "--public-url", null);
		if (base == null) {
			String h = bound.getAddress().isAnyLocalAddress() ? "localhost" : bound.getHostString();
			base = "http://" + (h.contains(":") ? "[" + h + "]" : h) + ":" + bound.getPort();
		}
		base = base.replaceAll("/+$", "");
		String secret = token == null ? "" : "/" + token;
		ctx.setFilesUrl(base + "/files" + secret + "/");
		warmUp();
		System.err.println("openrocket-mcp " + VERSION + " team server");
		System.err.println("  workspace:   " + workspace);
		System.err.println("  standards:   " + (ctx.standards().source() == null ? "built-in defaults" : ctx.standards().source()));
		System.err.println("  MCP URL:     " + base + "/mcp" + secret + "   (claude.ai: Settings > Connectors > Add custom connector)");
		if (token != null) {
			System.err.println("  or:          " + base + "/mcp  with header  Authorization: Bearer " + token);
		}
		System.err.println("  files page:  " + base + "/files" + secret + "/");
		System.err.println("  Keep the token secret: anyone with it can use the server and the workspace.");
		return t;
	}
}
