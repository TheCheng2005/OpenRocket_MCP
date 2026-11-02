package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Claude Code's and Codex's output read into the assistant's events, and the command lines they are started with. */
class AgentCliTest {
	/** What a sink was told, one line per call. */
	static final class Log implements AgentCli.Sink {
		final List<String> lines = new ArrayList<>();
		String session;

		@Override
		public void text(String text) {
			lines.add("text " + text);
		}

		@Override
		public void tool(String name, String input, String result, boolean ok, List<String> images) {
			lines.add("tool " + name + " " + input + " -> " + result + (ok ? "" : " (failed)") + (images.isEmpty() ? "" : " +" + images.size() + " image"));
		}

		@Override
		public void note(String text, boolean error) {
			lines.add((error ? "error " : "note ") + text);
		}

		@Override
		public void session(String id) {
			session = id;
		}
	}

	static void feed(AgentCli.Parser p, String... lines) {
		for (String l : lines) {
			p.line(JsonParser.parseString(l).getAsJsonObject());
		}
	}

	@Test
	void readsClaudeCode() {
		Log log = new Log();
		AgentCli.ClaudeParser p = new AgentCli.ClaudeParser(log);
		feed(p,
				"{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\",\"mcp_servers\":[{\"name\":\"rocketmaxxing\",\"status\":\"connected\"}]}",
				"{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\"}}",
				"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Checking.\"},{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"mcp__rocketmaxxing__design_status\",\"input\":{\"designId\":\"d1\"}}]}}",
				"{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[{\"type\":\"text\",\"text\":\"ready\"},{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AAAA\"}}]}]}}",
				"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"mcp__rocketmaxxing__open_design\",\"input\":{}}]}}",
				"{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"content\":\"Error: no path\",\"is_error\":true}]}}",
				"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"It is ready.\"}]}}");
		assertFalse(p.finished());
		feed(p, "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"It is ready.\",\"session_id\":\"s-1\"}");
		assertTrue(p.finished());
		assertEquals("s-1", log.session);
		assertEquals(List.of("text Checking.", "tool design_status {\"designId\":\"d1\"} -> ready +1 image",
				"tool open_design {} -> Error: no path (failed)", "text It is ready."), log.lines);

		Log out = new Log();
		AgentCli.ClaudeParser signedOut = new AgentCli.ClaudeParser(out);
		feed(signedOut, "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-2\",\"mcp_servers\":[{\"name\":\"rocketmaxxing\",\"status\":\"failed\"}]}",
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Not logged in · Please run /login\"}");
		assertTrue(out.lines.get(0).startsWith("error Claude Code could not reach the app's tools"), out.lines.toString());
		assertTrue(out.lines.get(1).contains("run claude and use /login"), out.lines.toString());
	}

	@Test
	void readsCodex() {
		Log log = new Log();
		AgentCli.CodexParser p = new AgentCli.CodexParser(log);
		feed(p, "{\"type\":\"thread.started\",\"thread_id\":\"th-1\"}", "{\"type\":\"turn.started\"}",
				"{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"mcp_tool_call\",\"server\":\"rocketmaxxing\",\"tool\":\"open_design\",\"arguments\":{\"example\":\"x\"},\"status\":\"in_progress\"}}",
				"{\"type\":\"item.completed\",\"item\":{\"id\":\"i1\",\"type\":\"mcp_tool_call\",\"server\":\"rocketmaxxing\",\"tool\":\"open_design\",\"arguments\":{\"example\":\"x\"},\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"designId\\\":\\\"d1\\\"}\"}]},\"error\":null,\"status\":\"completed\"}}",
				"{\"type\":\"item.completed\",\"item\":{\"id\":\"i2\",\"type\":\"mcp_tool_call\",\"tool\":\"save_design\",\"arguments\":{},\"result\":null,\"error\":{\"message\":\"denied\"},\"status\":\"failed\"}}",
				"{\"type\":\"item.completed\",\"item\":{\"id\":\"i3\",\"type\":\"reasoning\",\"text\":\"hmm\"}}",
				"{\"type\":\"item.completed\",\"item\":{\"id\":\"i4\",\"type\":\"agent_message\",\"text\":\"Opened it.\"}}",
				"{\"type\":\"turn.completed\",\"usage\":{}}");
		assertTrue(p.finished());
		assertEquals("th-1", log.session);
		assertEquals(List.of("tool open_design {\"example\":\"x\"} -> {\"designId\":\"d1\"}", "tool save_design {} -> denied (failed)",
				"text Opened it."), log.lines);

		Log failed = new Log();
		AgentCli.CodexParser f = new AgentCli.CodexParser(failed);
		feed(f, "{\"type\":\"turn.failed\",\"error\":{\"message\":\"unexpected status 401 Unauthorized\"}}");
		assertTrue(failed.lines.get(0).contains("run codex login"), failed.lines.toString());
	}

	@Test
	void commandLines() {
		Path ws = Path.of("ws");
		AgentCli.Turn claude = new AgentCli.Turn(AgentCli.Kind.CLAUDE_CODE, "sonnet", "s-1", "http://127.0.0.1:5/mcp", "secret-token", ws,
				"be brief", "hello");
		List<String> a = AgentCli.arguments(claude);
		assertFalse(String.join(" ", a).contains("secret-token"), "the token goes in the environment, not the command line");
		int tools = a.indexOf("--tools");
		assertEquals("", a.get(tools + 1), "no built-in tools");
		assertEquals("mcp__rocketmaxxing", a.get(a.indexOf("--allowedTools") + 1));
		assertTrue(a.contains("--strict-mcp-config"));
		JsonObject mcp = JsonParser.parseString(a.get(a.indexOf("--mcp-config") + 1)).getAsJsonObject().getAsJsonObject("mcpServers")
				.getAsJsonObject("rocketmaxxing");
		assertEquals("http", mcp.get("type").getAsString());
		assertEquals("Bearer ${ROCKETMAXXING_MCP_TOKEN}", mcp.getAsJsonObject("headers").get("Authorization").getAsString());
		assertEquals("s-1", a.get(a.indexOf("--resume") + 1));
		assertEquals("sonnet", a.get(a.indexOf("--model") + 1));

		AgentCli.Turn codex = new AgentCli.Turn(AgentCli.Kind.CODEX, "", "th-1", "http://127.0.0.1:5/mcp", "secret-token", ws, "be brief",
				"hello");
		List<String> c = AgentCli.arguments(codex);
		assertFalse(String.join(" ", c).contains("secret-token"));
		assertEquals("read-only", c.get(c.indexOf("--sandbox") + 1));
		assertTrue(c.contains("mcp_servers.rocketmaxxing.url=\"http://127.0.0.1:5/mcp\""), c.toString());
		assertTrue(c.contains("mcp_servers.rocketmaxxing.bearer_token_env_var=\"ROCKETMAXXING_MCP_TOKEN\""), c.toString());
		assertFalse(c.contains("-m"), "Codex's own default model");
		assertEquals(List.of("resume", "th-1", "-"), c.subList(c.size() - 3, c.size()));
	}
}
