package io.github.openrocketmcp.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The AI tools a team already has on the computer, used with their own subscriptions: Claude Code ({@code claude},
 * signed in with a Claude plan) and Codex ({@code codex}, signed in with a ChatGPT plan). Each runs headless with the
 * app's tools over MCP and nothing else: Claude Code without its built-in tools, Codex in its read-only sandbox. The app
 * never sees the sign-in; the tool keeps it, as it does in a terminal.
 */
final class AgentCli {
	enum Kind {
		CLAUDE_CODE("claude-code", "Claude Code", "claude"), CODEX("codex", "Codex", "codex");

		final String id, label, command;

		Kind(String id, String label, String command) {
			this.id = id;
			this.label = label;
			this.command = command;
		}

		static Kind of(String id) {
			for (Kind k : values()) {
				if (k.id.equals(id)) {
					return k;
				}
			}
			return null;
		}
	}

	/** The MCP server name the tools are offered under (Claude Code shows them as mcp__rocketmaxxing__tool). */
	static final String SERVER = "rocketmaxxing";
	static final String TOKEN_VAR = "ROCKETMAXXING_MCP_TOKEN";
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	private AgentCli() {
	}

	// ------------------------------------------------------------------------------------------- finding and checking

	/**
	 * The tool's program: on PATH, or where its installers put it. An app started from the Dock or the Start menu does
	 * not get the terminal's PATH, so the usual places are searched too. ROCKETMAXXING_CLAUDE / ROCKETMAXXING_CODEX
	 * point at a particular one.
	 */
	static Path find(Kind k) {
		String override = System.getenv("ROCKETMAXXING_" + (k == Kind.CLAUDE_CODE ? "CLAUDE" : "CODEX"));
		if (override != null && !override.isBlank() && Files.isRegularFile(Path.of(override))) {
			return Path.of(override);
		}
		for (Path dir : searchPath()) {
			for (String name : WINDOWS ? new String[] { k.command + ".exe", k.command + ".cmd" } : new String[] { k.command }) {
				Path p = dir.resolve(name);
				if (Files.isRegularFile(p) && (WINDOWS || Files.isExecutable(p))) {
					return p;
				}
			}
		}
		return null;
	}

	static List<Path> searchPath() {
		List<Path> dirs = new ArrayList<>();
		String path = System.getenv("PATH");
		if (path != null) {
			for (String d : path.split(File.pathSeparator)) {
				if (!d.isBlank()) {
					dirs.add(Path.of(d));
				}
			}
		}
		String home = System.getProperty("user.home");
		for (String d : new String[] { ".local/bin", ".claude/local", ".npm-global/bin", ".volta/bin", ".bun/bin", ".cargo/bin" }) {
			dirs.add(Path.of(home, d));
		}
		if (WINDOWS) {
			String appData = System.getenv("APPDATA");
			if (appData != null) {
				dirs.add(Path.of(appData, "npm"));
			}
		} else {
			dirs.add(Path.of("/opt/homebrew/bin"));
			dirs.add(Path.of("/usr/local/bin"));
		}
		return dirs;
	}

	/** Whether the tool is installed and signed in, from its own commands. */
	static Map<String, Object> status(Kind k) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", k.id);
		out.put("label", k.label);
		Path exe = find(k);
		out.put("installed", exe != null);
		if (exe == null) {
			out.put("detail", k == Kind.CLAUDE_CODE ? "Not found. Install Claude Code (claude.com/claude-code), then sign in by running claude in a terminal."
					: "Not found. Install Codex (npm i -g @openai/codex), then sign in with codex login.");
			return out;
		}
		out.put("path", exe.toString());
		Result v = run(exe, List.of("--version"), 20);
		java.util.regex.Matcher ver = java.util.regex.Pattern.compile("\\d+(\\.\\d+)+").matcher(v.out);
		out.put("version", ver.find() ? ver.group() : v.out.lines().findFirst().orElse("").trim());
		if (k == Kind.CLAUDE_CODE) {
			Result a = run(exe, List.of("auth", "status", "--json"), 20);
			try {
				JsonObject s = JsonParser.parseString(a.out).getAsJsonObject();
				boolean in = s.has("loggedIn") && s.get("loggedIn").getAsBoolean();
				out.put("signedIn", in);
				String method = s.has("authMethod") ? s.get("authMethod").getAsString() : "";
				out.put("detail", !in ? "Not signed in: run claude in a terminal and sign in with your Claude account."
						: method.contains("api_key") ? "Signed in with an API key (billed to that API account)."
								: "Signed in with your Claude account (uses your plan's limits).");
			} catch (RuntimeException e) {
				out.put("detail", "Could not read the sign-in status; run claude in a terminal to check.");
			}
		} else {
			Result a = run(exe, List.of("login", "status"), 20);
			String text = (a.out + "\n" + a.err).lines().filter(l -> !l.startsWith("WARNING")).filter(l -> !l.isBlank())
					.findFirst().orElse("").trim();
			out.put("signedIn", a.code == 0);
			out.put("detail", a.code == 0 ? text + (text.toLowerCase(Locale.ROOT).contains("chatgpt") ? " (uses your plan's limits)." : ".")
					: "Not signed in: run codex login in a terminal and sign in with your ChatGPT account.");
		}
		return out;
	}

	record Result(int code, String out, String err) {
	}

	private static Result run(Path exe, List<String> args, int seconds) {
		List<String> cmd = new ArrayList<>(command(exe));
		cmd.addAll(args);
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			env(pb, exe, null);
			Process p = pb.start();
			p.getOutputStream().close();
			// Short outputs: read both after it ends, within the time limit.
			if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return new Result(-1, "", "timed out");
			}
			return new Result(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
					new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			return new Result(-1, "", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(-1, "", "interrupted");
		}
	}

	/** npm installs .cmd shims on Windows, which only cmd.exe can start. */
	private static List<String> command(Path exe) {
		return WINDOWS && exe.toString().toLowerCase(Locale.ROOT).endsWith(".cmd") ? List.of("cmd.exe", "/c", exe.toString())
				: List.of(exe.toString());
	}

	/**
	 * The child's environment: the user's own (so their sign-in and settings apply), the tool's folder on PATH (npm
	 * tools need node beside them), the MCP token, and none of the variables that would make it a sub-session of a
	 * Claude Code session the app happens to be started from.
	 */
	private static void env(ProcessBuilder pb, Path exe, String token) {
		Map<String, String> env = pb.environment();
		for (String v : List.of("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SESSION_ID", "CLAUDE_CODE_CHILD_SESSION")) {
			env.remove(v);
		}
		String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
		StringBuilder p = new StringBuilder(exe.getParent().toString());
		for (Path d : searchPath()) {
			p.append(File.pathSeparator).append(d);
		}
		env.put(env.containsKey("Path") ? "Path" : "PATH", p + File.pathSeparator + path);
		if (token != null) {
			env.put(TOKEN_VAR, token);
		}
	}

	// ------------------------------------------------------------------------------------------- running a turn

	/** One turn's settings: the tool, its model ("" for its own default), the session to continue, where the tools are. */
	record Turn(Kind kind, String model, String resume, String mcpUrl, String token, Path cwd, String system, String prompt) {
	}

	/** What a turn reports back: Claude's (or Codex's) words, a tool call and its result, a note, an error, the session. */
	interface Sink {
		void text(String text);

		void tool(String name, String input, String result, boolean ok, List<String> images);

		void note(String text, boolean error);

		void session(String id);
	}

	static List<String> arguments(Turn t) {
		List<String> a = new ArrayList<>();
		if (t.kind == Kind.CLAUDE_CODE) {
			String mcp = "{\"mcpServers\":{\"" + SERVER + "\":{\"type\":\"http\",\"url\":\"" + t.mcpUrl
					+ "\",\"headers\":{\"Authorization\":\"Bearer ${" + TOKEN_VAR + "}\"}}}}";
			a.addAll(List.of("-p", "--output-format", "stream-json", "--verbose", "--mcp-config", mcp, "--strict-mcp-config",
					// No built-in tools (shell, files, web): only the app's.
					"--tools", "", "--allowedTools", "mcp__" + SERVER, "--append-system-prompt", t.system));
			if (!t.model.isBlank()) {
				a.addAll(List.of("--model", t.model));
			}
			if (t.resume != null) {
				a.addAll(List.of("--resume", t.resume));
			}
		} else {
			a.addAll(List.of("exec", "--json", "--skip-git-repo-check", "--sandbox", "read-only", "-C", t.cwd.toString(),
					"-c", "mcp_servers." + SERVER + ".url=\"" + t.mcpUrl + "\"",
					"-c", "mcp_servers." + SERVER + ".bearer_token_env_var=\"" + TOKEN_VAR + "\"",
					"-c", "mcp_servers." + SERVER + ".tool_timeout_sec=900"));
			if (!t.model.isBlank()) {
				a.addAll(List.of("-m", t.model));
			}
			if (t.resume != null) {
				a.addAll(List.of("resume", t.resume));
			}
			a.add("-"); // the prompt comes on stdin
		}
		return a;
	}

	/** Starts the tool on one turn; the returned process ends when the turn does. */
	static Process start(Turn t, Path exe) throws IOException {
		List<String> cmd = new ArrayList<>(command(exe));
		cmd.addAll(arguments(t));
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(t.cwd.toFile());
		env(pb, exe, t.token);
		Process p = pb.start();
		try (OutputStream in = p.getOutputStream()) {
			String prompt = t.kind == Kind.CODEX && t.resume == null
					? "<instructions>\n" + t.system + "\n</instructions>\n\n" + t.prompt // Codex has no system-prompt option
					: t.prompt;
			in.write(prompt.getBytes(StandardCharsets.UTF_8));
		}
		return p;
	}

	/** Reads the tool's JSON lines until it ends, passing what happened to the sink; returns the last error text, if any. */
	static String read(Kind kind, Process p, Sink sink) throws IOException {
		StringBuilder err = new StringBuilder();
		Thread errReader = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					synchronized (err) {
						if (err.length() < 20_000) {
							err.append(line).append('\n');
						}
					}
				}
			} catch (IOException ignored) {
				// the process went away
			}
		}, "agent-stderr");
		errReader.setDaemon(true);
		errReader.start();
		Parser parser = kind == Kind.CLAUDE_CODE ? new ClaudeParser(sink) : new CodexParser(sink);
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank() || !line.trim().startsWith("{")) {
					continue;
				}
				try {
					parser.line(JsonParser.parseString(line).getAsJsonObject());
				} catch (RuntimeException notOurs) {
					// a line in a format this version does not know
				}
			}
		}
		try {
			p.waitFor(30, TimeUnit.SECONDS);
			errReader.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (err) {
			return parser.finished() ? null : err.toString().lines().filter(l -> !l.startsWith("WARNING")).reduce((x, y) -> y).orElse("");
		}
	}

	interface Parser {
		void line(JsonObject o);

		/** Whether the turn ended normally (otherwise the caller reports the process's last error line). */
		boolean finished();
	}

	/** Claude Code's stream-json: init (session), assistant messages (text, tool_use), user messages (tool_result), result. */
	static final class ClaudeParser implements Parser {
		private final Sink sink;
		private final Map<String, String[]> calls = new LinkedHashMap<>();
		private boolean done;

		ClaudeParser(Sink sink) {
			this.sink = sink;
		}

		@Override
		public void line(JsonObject o) {
			String type = str(o, "type");
			if (type.equals("system") && str(o, "subtype").equals("init")) {
				sink.session(str(o, "session_id"));
				if (o.has("mcp_servers")) {
					for (JsonElement s : o.getAsJsonArray("mcp_servers")) {
						JsonObject so = s.getAsJsonObject();
						if (SERVER.equals(str(so, "name")) && !"connected".equals(str(so, "status"))) {
							sink.note("Claude Code could not reach the app's tools (" + str(so, "status") + ").", true);
						}
					}
				}
			} else if (type.equals("assistant") && o.has("message")) {
				for (JsonElement c : content(o.getAsJsonObject("message"))) {
					JsonObject b = c.getAsJsonObject();
					if (str(b, "type").equals("text") && !str(b, "text").isBlank()) {
						sink.text(str(b, "text"));
					} else if (str(b, "type").equals("tool_use")) {
						calls.put(str(b, "id"), new String[] { str(b, "name"), b.has("input") ? b.get("input").toString() : "{}" });
					}
				}
			} else if (type.equals("user") && o.has("message")) {
				for (JsonElement c : content(o.getAsJsonObject("message"))) {
					JsonObject b = c.getAsJsonObject();
					if (!str(b, "type").equals("tool_result")) {
						continue;
					}
					String[] call = calls.remove(str(b, "tool_use_id"));
					String name = call == null ? "tool" : call[0].replaceFirst("^mcp__" + SERVER + "__", "");
					List<String> images = new ArrayList<>();
					String text = resultText(b.get("content"), images);
					sink.tool(name, call == null ? "{}" : call[1], text, !(b.has("is_error") && b.get("is_error").getAsBoolean()), images);
				}
			} else if (type.equals("result")) {
				done = true;
				sink.session(str(o, "session_id"));
				boolean failed = o.has("is_error") && o.get("is_error").getAsBoolean() || str(o, "subtype").startsWith("error");
				if (failed) {
					String why = str(o, "result");
					if (why.isBlank() && o.has("errors")) {
						why = o.get("errors").toString();
					}
					sink.note(hint(Kind.CLAUDE_CODE, why.isBlank() ? "Claude Code stopped (" + str(o, "subtype") + ")." : why), true);
				}
			}
		}

		@Override
		public boolean finished() {
			return done;
		}
	}

	/** Codex's exec JSON lines: thread.started (session), item.completed (agent_message, mcp_tool_call, command), errors. */
	static final class CodexParser implements Parser {
		private final Sink sink;
		private boolean done;

		CodexParser(Sink sink) {
			this.sink = sink;
		}

		@Override
		public void line(JsonObject o) {
			String type = str(o, "type");
			switch (type) {
				case "thread.started" -> sink.session(str(o, "thread_id"));
				case "turn.completed" -> done = true;
				case "turn.failed" -> {
					done = true;
					JsonObject e = o.has("error") && o.get("error").isJsonObject() ? o.getAsJsonObject("error") : new JsonObject();
					sink.note(hint(Kind.CODEX, str(e, "message")), true);
				}
				case "error" -> sink.note(hint(Kind.CODEX, str(o, "message")), true);
				case "item.completed" -> {
					JsonObject item = o.getAsJsonObject("item");
					switch (str(item, "type")) {
						case "agent_message" -> {
							if (!str(item, "text").isBlank()) {
								sink.text(str(item, "text"));
							}
						}
						case "mcp_tool_call" -> {
							List<String> images = new ArrayList<>();
							String text = "";
							if (item.has("result") && item.get("result").isJsonObject()) {
								text = resultText(item.getAsJsonObject("result").get("content"), images);
							}
							boolean ok = !(item.has("error") && !item.get("error").isJsonNull()) && !"failed".equals(str(item, "status"));
							if (!ok && item.has("error") && !item.get("error").isJsonNull()) {
								JsonElement e = item.get("error");
								text = e.isJsonObject() ? str(e.getAsJsonObject(), "message") : e.getAsString();
							}
							sink.tool(str(item, "tool"), item.has("arguments") ? item.get("arguments").toString() : "{}", text, ok, images);
						}
						case "command_execution" -> sink.tool("shell: " + str(item, "command"), "{}", str(item, "aggregated_output"),
								!"failed".equals(str(item, "status")), List.of());
						case "error" -> sink.note(str(item, "message"), false);
						default -> {
							// reasoning, plans and other items are not shown
						}
					}
				}
				default -> {
					// turn.started, item.started, item.updated
				}
			}
		}

		@Override
		public boolean finished() {
			return done;
		}
	}

	/** A sign-in problem said in the words of what to do about it. */
	static String hint(Kind k, String message) {
		String m = message == null ? "" : message.trim();
		String low = m.toLowerCase(Locale.ROOT);
		boolean auth = low.contains("login") || low.contains("log in") || low.contains("sign in") || low.contains("401")
				|| low.contains("unauthorized") || low.contains("api key") || low.contains("not logged");
		if (!auth) {
			return m;
		}
		return m + (k == Kind.CLAUDE_CODE ? " Sign in: open a terminal, run claude and use /login."
				: " Sign in: open a terminal and run codex login.");
	}

	private static JsonArray content(JsonObject message) {
		JsonElement c = message.get("content");
		if (c != null && c.isJsonArray()) {
			return c.getAsJsonArray();
		}
		JsonArray a = new JsonArray();
		if (c != null && c.isJsonPrimitive()) {
			JsonObject t = new JsonObject();
			t.addProperty("type", "text");
			t.addProperty("text", c.getAsString());
			a.add(t);
		}
		return a;
	}

	/** A tool result's text, with any images (as data URLs) collected on the side. */
	static String resultText(JsonElement content, List<String> images) {
		if (content == null || content.isJsonNull()) {
			return "";
		}
		if (content.isJsonPrimitive()) {
			return content.getAsString();
		}
		StringBuilder b = new StringBuilder();
		for (JsonElement e : content.getAsJsonArray()) {
			JsonObject c = e.getAsJsonObject();
			String type = str(c, "type");
			if (type.equals("text")) {
				b.append(str(c, "text"));
			} else if (type.equals("image")) {
				// Claude Code: {source: {data, media_type}}; MCP (Codex): {data, mimeType}
				JsonObject src = c.has("source") && c.get("source").isJsonObject() ? c.getAsJsonObject("source") : c;
				String data = str(src, "data");
				String mime = !str(src, "media_type").isEmpty() ? str(src, "media_type") : !str(src, "mimeType").isEmpty() ? str(src, "mimeType") : "image/png";
				if (!data.isEmpty()) {
					images.add("data:" + mime + ";base64," + data);
				}
			}
		}
		return b.toString();
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
	}

	/** Ends the tool and anything it started. */
	static void kill(Process p) {
		if (p == null) {
			return;
		}
		p.descendants().forEach(ProcessHandle::destroy);
		p.destroy();
	}
}
