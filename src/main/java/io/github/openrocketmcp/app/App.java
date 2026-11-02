package io.github.openrocketmcp.app;

import java.awt.Desktop;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.or.OrRuntime;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.tools.DesignLocks;

/**
 * RocketMaxxing, the desktop app: the same engine and tools as the MCP server, with its own interface, offline. Starts
 * the local server and opens the interface in a browser window; stops when that window has been closed for a while.
 */
public final class App {
	public static final String NAME = "RocketMaxxing";
	public static final String VERSION = "0.1.0";

	static final String USAGE = """
			Usage: rocketmaxxing [options]
			  --workspace DIR   folder for designs (default: ~/RocketMaxxing)
			  --port N          local port (default: any free one)
			  --ui DIR          folder of the built interface (default: next to the program)
			  --no-browser      do not open a window; print the address instead
			  --dev URL         also accept calls from a development server, e.g. http://localhost:5173
			  FILE.ork ...      designs to open (a running RocketMaxxing for the same folder opens them instead)
			""";

	/** The page's address once the server is up; files that arrive before that wait in {@code pending}. */
	private static String baseUrl;
	private static boolean windows;
	private static final List<String> pending = new ArrayList<>();

	private App() {
	}

	public static void main(String[] args) throws Exception {
		List<String> a = List.of(args);
		if (a.contains("--help") || a.contains("-h")) {
			System.out.print(USAGE);
			return;
		}
		if (System.getProperty("java.awt.headless") == null) {
			// The system's file dialogs need a screen; a Linux machine without one (a server, a container) has none.
			boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
			boolean screen = !linux || notBlank(System.getenv("DISPLAY")) || notBlank(System.getenv("WAYLAND_DISPLAY"));
			System.setProperty("java.awt.headless", screen ? "false" : "true");
		}
		Path ws = Path.of(opt(a, "--workspace", System.getProperty("user.home") + File.separator + NAME)).toAbsolutePath().normalize();
		Files.createDirectories(ws);
		List<String> files = designFiles(a);
		boolean noBrowser = a.contains("--no-browser");
		String devUrl = opt(a, "--dev", null);

		// One app per designs folder: a later launch hands its files to the running one and exits.
		Instance instance = devUrl == null ? new Instance(ws) : null;
		if (instance != null && !instance.claim()) {
			String running = instance.handOver(files, !noBrowser, 90_000);
			if (running != null) {
				if (noBrowser) {
					System.out.println("Open " + running);
				}
				Log.info(NAME + " is already running for " + ws + "; handed over to it.");
				System.exit(0);
			}
			Log.info("Another " + NAME + " holds " + ws + " but does not answer; starting anyway.");
			instance = null;
		}

		// macOS: the Dock and Finder talk to the app from the start (a design double-clicked to launch it arrives now).
		AtomicReference<Context> ctxRef = new AtomicReference<>();
		boolean dock = devUrl == null && MacDesktop.install(() -> show(List.of()), App::show, () -> unsaved(ctxRef.get()));

		OrRuntime.init(); // before any request: OpenRocket's databases fail for good if touched before it starts
		Context ctx = new Context(Standards.discover(ws), ws, false);
		ctxRef.set(ctx);
		McpServer server = Main.build(ctx, s -> AppTools.register(s, ctx), false);
		server.guard(new DesignLocks(ctx));
		server.handleLine("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
				+ "\"capabilities\":{},\"clientInfo\":{\"name\":\"" + NAME + "\",\"version\":\"" + VERSION + "\"}}}");
		Main.warmUp();
		List<String> dev = new ArrayList<>();
		if (devUrl != null) {
			dev.add(devUrl.replaceAll("/+$", ""));
		}
		AppServer app = new AppServer(server, ui(opt(a, "--ui", System.getProperty("rocketmaxxing.ui"))), dev);
		app.files(ws);
		server.tool(new ToolDef("app_window", "App: window", "Opens a window of the running app (another launch asks for it), "
				+ "with designs to open; returns its address.",
				Schema.object().array("files", "Design files to open.", Schema.type("string", null), false)
						.bool("window", "Open a window (default true).", false).build(),
				false, x -> {
					List<String> given = x.has("files") ? x.strList("files") : List.of();
					if (x.bool("window", true)) {
						show(given);
					}
					return Map.of("url", withFiles(app.url(), given));
				}));
		InetSocketAddress bound = app.start(Integer.parseInt(opt(a, "--port", "0")));
		Instance held = instance;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			app.stop();
			if (held != null) {
				held.release();
			}
		}));
		String url = app.url();
		if (instance != null) {
			instance.publish(bound.getPort(), app.token());
		}
		Log.info(NAME + " " + VERSION + " (engine " + Main.VERSION + ") on http://127.0.0.1:" + bound.getPort() + " ; workspace " + ws
				+ (MacDesktop.ACTIVE.isEmpty() ? "" : " ; macOS: " + String.join(", ", MacDesktop.ACTIVE)));
		boolean window = !noBrowser && devUrl == null && ready(url, files);
		if (!window) {
			System.out.println("Open " + withFiles(url, files));
			if (devUrl != null) {
				System.out.println("Development: open " + devUrl + "/#token=" + app.token() + "&port=" + bound.getPort());
			}
			new CountDownLatch(1).await();
			return;
		}
		if (dock) {
			// macOS: the app stays in the Dock with no window, as Mac apps do, until it is quit.
			new CountDownLatch(1).await();
		}
		// Desktop use: stop once the window has been gone for two minutes (the page checks in every 20 s).
		while (true) {
			Thread.sleep(15_000);
			long seen = app.lastSeen();
			if (seen > 0 && System.currentTimeMillis() - seen > 120_000) {
				Log.info("Window closed; stopping.");
				app.stop();
				System.exit(0);
			}
		}
	}

	/** Opens the first window, with the files given at launch and any that arrived while starting. */
	private static synchronized boolean ready(String url, List<String> files) {
		List<String> first = new ArrayList<>(files);
		first.addAll(pending);
		pending.clear();
		windows = open(withFiles(url, first));
		baseUrl = url;
		return windows;
	}

	/**
	 * A window with these designs open. The page keeps one window: a new one replaces the one before (a browser
	 * window cannot be brought to the front from here), so the Dock, a second launch and Finder all land in front.
	 */
	static synchronized void show(List<String> files) {
		if (baseUrl == null) {
			pending.addAll(files);
			return;
		}
		if (windows) {
			open(withFiles(baseUrl, files));
		}
	}

	static String withFiles(String url, List<String> files) {
		StringBuilder b = new StringBuilder(url);
		for (String f : files) {
			b.append("&open=").append(java.net.URLEncoder.encode(f, java.nio.charset.StandardCharsets.UTF_8));
		}
		return b.toString();
	}

	/** The design files among the arguments (what Windows and Linux pass for a double-clicked file). */
	static List<String> designFiles(List<String> a) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < a.size(); i++) {
			String s = a.get(i);
			if (s.startsWith("--")) {
				if (List.of("--workspace", "--port", "--ui", "--dev").contains(s)) {
					i++;
				}
				continue;
			}
			if (s.toLowerCase(Locale.ROOT).matches(".*\\.(ork|rkt)") && Files.isRegularFile(Path.of(s))) {
				out.add(Path.of(s).toAbsolutePath().normalize().toString());
			}
		}
		return out;
	}

	/** Names of open designs with unsaved changes. */
	static List<String> unsaved(Context ctx) {
		List<String> out = new ArrayList<>();
		if (ctx != null) {
			for (var d : ctx.designs.all()) {
				if (!d.doc.isSaved()) {
					out.add(d.name());
				}
			}
		}
		return out;
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	static String opt(List<String> a, String name, String fallback) {
		int i = a.indexOf(name);
		return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : fallback;
	}

	/**
	 * The interface: --ui, else the "ui" folder of the installation (next to its lib folder, or next to the jars in an
	 * installer's app folder), else the copy in the jar.
	 */
	static Path ui(String given) {
		if (given != null) {
			return Path.of(given).toAbsolutePath().normalize();
		}
		try {
			Path jar = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			for (Path ui : List.of(jar.getParent().getParent().resolve("ui"), jar.getParent().resolve("ui"))) {
				if (Files.isRegularFile(ui.resolve("index.html"))) {
					return ui.toAbsolutePath().normalize();
				}
			}
		} catch (Exception ignored) {
			// not run from an installation
		}
		return null;
	}

	/** Opens the page in its own window when Chrome or Edge is installed (app mode), else in the default browser. */
	static boolean open(String url) {
		for (String browser : browsers()) {
			if (new File(browser).canExecute()) {
				try {
					new ProcessBuilder(browser, "--app=" + url, "--new-window").inheritIO().start();
					return true;
				} catch (Exception ignored) {
					// try the next
				}
			}
		}
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
				return true;
			}
		} catch (Exception e) {
			Log.info("Could not open a browser: " + e.getMessage());
		}
		return false;
	}

	static List<String> browsers() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
			String pf86 = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
			String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
			return List.of(pf86 + "\\Microsoft\\Edge\\Application\\msedge.exe", pf + "\\Microsoft\\Edge\\Application\\msedge.exe",
					pf + "\\Google\\Chrome\\Application\\chrome.exe", pf86 + "\\Google\\Chrome\\Application\\chrome.exe",
					local + "\\Google\\Chrome\\Application\\chrome.exe");
		}
		if (os.contains("mac")) {
			return List.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
					"/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
					"/Applications/Chromium.app/Contents/MacOS/Chromium");
		}
		return List.of("/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/microsoft-edge");
	}
}
