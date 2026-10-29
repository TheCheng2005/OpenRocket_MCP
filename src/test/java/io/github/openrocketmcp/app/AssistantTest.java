package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/** The assistant's loop against a stand-in for the Messages API: a tool call, its result, then the answer. */
class AssistantTest {
	HttpServer api;
	final List<JsonObject> requests = new ArrayList<>();
	final List<String> betas = new ArrayList<>();

	@AfterEach
	void stopApi() {
		if (api != null) {
			api.stop(0);
		}
		System.clearProperty("rocketmaxxing.anthropicBaseUrl");
	}

	void startApi(List<String> replies) throws IOException {
		api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		api.createContext("/v1/messages", ex -> {
			JsonObject body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			String reply;
			synchronized (requests) {
				requests.add(body);
				betas.add(String.valueOf(ex.getRequestHeaders().getFirst("anthropic-beta")));
				reply = replies.get(Math.min(requests.size() - 1, replies.size() - 1));
			}
			byte[] out = reply.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		api.start();
		System.setProperty("rocketmaxxing.anthropicBaseUrl", "http://127.0.0.1:" + api.getAddress().getPort());
	}

	static String message(String stop, String content) {
		return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":" + content
				+ ",\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
	}

	JsonObject call(McpServer s, String tool, String args) {
		JsonObject r = s.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":" + args + "}}").getAsJsonObject("result");
		String text = r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertFalse(r.get("isError").getAsBoolean(), tool + ": " + text);
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void runsToolsAndAnswers(@TempDir Path home) throws Exception {
		Context ctx = new Context(Standards.defaults());
		Path settings = home.resolve("assistant.json");
		McpServer s = Main.build(ctx, x -> AppTools.register(x, ctx, settings), false);
		String d = call(s, "open_design", "{\"example\": \"A simple model rocket\"}").get("designId").getAsString();

		// Off until the team turns it on, and the key never comes back to the page.
		assertFalse(call(s, "app_assistant", "{}").get("enabled").getAsBoolean());
		JsonObject set = call(s, "app_assistant_settings", "{\"enabled\": true, \"key\": \"sk-test\", \"model\": \"claude-sonnet-5-5\"}");
		assertTrue(set.get("hasKey").getAsBoolean());
		assertFalse(set.toString().contains("sk-test"));
		assertTrue(Files.readString(settings).contains("sk-test"), "kept in the settings file");

		startApi(List.of(
				message("tool_use", "[{\"type\":\"text\",\"text\":\"Checking the rules.\"},{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"design_status\",\"input\":{\"designId\":\"" + d + "\"}}]"),
				message("end_turn", "[{\"type\":\"text\",\"text\":\"It is ready.\"}]")));
		call(s, "app_assistant_send", "{\"text\": \"Is it ready?\", \"designId\": \"" + d + "\"}");
		JsonObject poll = null;
		for (int i = 0; i < 300; i++) {
			poll = call(s, "app_assistant_poll", "{}");
			if (!poll.get("running").getAsBoolean()) {
				break;
			}
			Thread.sleep(100);
		}
		List<String> kinds = new ArrayList<>();
		for (JsonElement e : poll.getAsJsonArray("events")) {
			kinds.add(e.getAsJsonObject().get("kind").getAsString());
		}
		assertEquals(List.of("you", "claude", "tool", "claude"), kinds, poll.toString());
		JsonObject tool = poll.getAsJsonArray("events").get(2).getAsJsonObject();
		assertTrue(tool.get("ok").getAsBoolean(), tool.toString());
		assertEquals("design_status", tool.get("tool").getAsString());
		assertFalse(tool.get("changed").getAsBoolean(), "a status report changes nothing");

		// The requests: the engine's tools (not the app's), cached; the design in the message; the tool's result sent back.
		assertEquals(2, requests.size());
		JsonObject first = requests.get(0);
		assertEquals("claude-sonnet-5-5", first.get("model").getAsString());
		JsonArray tools = first.getAsJsonArray("tools");
		assertTrue(tools.size() > 50);
		for (JsonElement t : tools) {
			assertFalse(t.getAsJsonObject().get("name").getAsString().startsWith("app_"));
		}
		assertTrue(tools.get(tools.size() - 1).getAsJsonObject().has("cache_control"));
		assertTrue(first.getAsJsonArray("system").get(0).getAsJsonObject().has("cache_control"));
		assertEquals("default", first.get("fallbacks").getAsString());
		assertTrue(betas.get(0).contains("server-side-fallback-2026-07-01"), betas.get(0));
		assertTrue(first.getAsJsonArray("messages").get(0).toString().contains("designId " + d));
		JsonArray second = requests.get(1).getAsJsonArray("messages");
		assertEquals(3, second.size());
		JsonObject result = second.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
		assertEquals("tool_result", result.get("type").getAsString());
		assertEquals("tu_1", result.get("tool_use_id").getAsString());

		// A refused request is said plainly; a failed turn leaves the conversation as it was.
		requests.clear();
		api.stop(0);
		startApi(List.of(message("refusal", "[]")));
		call(s, "app_assistant_send", "{\"text\": \"Something else\"}");
		for (int i = 0; i < 300 && call(s, "app_assistant_poll", "{}").get("running").getAsBoolean(); i++) {
			Thread.sleep(100);
		}
		JsonArray all = call(s, "app_assistant_poll", "{\"from\": 4}").getAsJsonArray("events");
		assertEquals("error", all.get(all.size() - 1).getAsJsonObject().get("kind").getAsString());
		assertTrue(all.get(all.size() - 1).getAsJsonObject().get("text").getAsString().contains("declined"), all.toString());
		assertEquals(Map.of("reset", true).toString().replace("=", ":").replace("{", "{\"").replace(":", "\":"),
				call(s, "app_assistant_reset", "{}").toString());
	}
}
