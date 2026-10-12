package io.github.openrocketmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.units.Dim;

/** JSON-RPC / MCP protocol behavior, independent of OpenRocket. */
class McpServerTest {
	McpServer s;

	@BeforeEach
	void setUp() {
		s = new McpServer("test", "0.0.1", "instructions");
		s.tool(new ToolDef("echo", "Echo", "Echo a quantity.", Schema.object().qty("length", "A length.", true).build(), true,
				a -> Map.of("si", a.qty("length", Dim.LENGTH))));
		s.tool(new ToolDef("fail", "Fail", "Always fails.", Schema.object().build(), true, a -> {
			throw new ToolException("nope: explain what to do instead");
		}));
		s.tool(new ToolDef("crash", "Crash", "Throws an unexpected exception.", Schema.object().build(), true, a -> {
			throw new IllegalStateException("boom");
		}));
	}

	JsonObject call(String line) {
		return s.handleLine(line);
	}

	@Test
	void negotiatesProtocolVersion() {
		for (String v : new String[] { "2025-06-18", "2025-03-26", "2024-11-05" }) {
			JsonObject r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + v + "\"}}");
			assertEquals(v, r.getAsJsonObject("result").get("protocolVersion").getAsString());
		}
		JsonObject r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
		assertEquals("2025-06-18", r.getAsJsonObject("result").get("protocolVersion").getAsString(), "falls back to latest");
		JsonObject res = r.getAsJsonObject("result");
		assertTrue(res.getAsJsonObject("capabilities").has("tools"));
		assertEquals("instructions", res.get("instructions").getAsString());
	}

	@Test
	void jsonRpcErrors() {
		assertEquals(-32700, call("{not json").getAsJsonObject("error").get("code").getAsInt());
		assertEquals(-32601, call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"bogus\"}").getAsJsonObject("error").get("code").getAsInt());
		assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"missing\"}}")
				.getAsJsonObject("error").get("code").getAsInt());
		// id is echoed back, including string ids
		assertEquals("abc", call("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}").get("id").getAsString());
	}

	@Test
	void notificationsAndResponsesGetNoReply() {
		assertNull(call("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
		assertNull(call("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}"));
		assertNull(call("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}"), "responses to our own requests are ignored");
		assertNull(call("{\"jsonrpc\":\"2.0\",\"method\":\"bogus\"}"), "no error reply to a notification");
	}

	@Test
	void toolResultsAndErrors() {
		JsonObject ok = call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"length\":\"2 in\"}}}");
		JsonObject res = ok.getAsJsonObject("result");
		assertFalse(res.get("isError").getAsBoolean());
		String text = res.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertEquals(0.0508, JsonParser.parseString(text).getAsJsonObject().get("si").getAsDouble(), 1e-12);

		// Tool errors are results with isError (the model can read and recover), not protocol errors
		JsonObject fail = call("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"fail\"}}");
		assertTrue(fail.getAsJsonObject("result").get("isError").getAsBoolean());
		assertTrue(fail.toString().contains("explain what to do instead"));

		JsonObject crash = call("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"crash\"}}");
		assertTrue(crash.getAsJsonObject("result").get("isError").getAsBoolean());
		assertTrue(crash.toString().contains("boom"));

		// Wrong dimension and missing required argument are readable tool errors
		JsonObject dim = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"length\":\"20 ft/s\"}}}");
		assertTrue(dim.getAsJsonObject("result").get("isError").getAsBoolean());
		assertTrue(dim.toString().contains("velocity"), dim.toString());
		JsonObject missing = call("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{}}}");
		assertTrue(missing.getAsJsonObject("result").get("isError").getAsBoolean());
		assertTrue(missing.toString().contains("length"));
	}

	@Test
	void toolListCarriesSchemasAndAnnotations() {
		JsonObject r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
		var tools = r.getAsJsonObject("result").getAsJsonArray("tools");
		assertEquals(3, tools.size());
		JsonObject echo = tools.get(0).getAsJsonObject();
		assertEquals("object", echo.getAsJsonObject("inputSchema").get("type").getAsString());
		assertEquals("length", echo.getAsJsonObject("inputSchema").getAsJsonArray("required").get(0).getAsString());
		assertTrue(echo.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
	}

	@Test
	void interactiveViewPlumbing() {
		JsonObject ui = JsonParser.parseString("{\"ui\":{\"resourceUri\":\"ui://t/view.html\",\"visibility\":[\"app\"]}}")
				.getAsJsonObject();
		ToolDef data = new ToolDef("view_data", "Data", "For the view.", Schema.object().build(), true, a -> {
			CallContext.current().structured(Map.of("points", 3));
			return "three points";
		}, ui);
		assertTrue(data.appOnly());
		assertTrue(data.withHandler(a -> "x").appOnly(), "wrappers keep the metadata");
		s.tool(data);
		s.resource(new McpServer.Resource("ui://t/view.html", "View", "A view.", McpServer.UI_MIME, () -> "<!doctype html>",
				JsonParser.parseString("{\"ui\":{\"prefersBorder\":true}}").getAsJsonObject(), false));

		JsonObject listed = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").getAsJsonObject("result");
		JsonObject tool = null;
		for (var t : listed.getAsJsonArray("tools")) {
			if (t.getAsJsonObject().get("name").getAsString().equals("view_data")) {
				tool = t.getAsJsonObject();
			}
		}
		assertTrue(tool != null, "app-only tools stay listed (hosts hide them from the model)");
		assertEquals("ui://t/view.html", tool.getAsJsonObject("_meta").getAsJsonObject("ui").get("resourceUri").getAsString());

		JsonObject r = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"view_data\"}}")
				.getAsJsonObject("result");
		assertEquals(3, r.getAsJsonObject("structuredContent").get("points").getAsInt());
		assertEquals("three points", r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());

		JsonObject resources = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}").getAsJsonObject("result");
		assertFalse(resources.toString().contains("ui://t/view.html"), "a view resource is reached through its tool only");
		JsonObject read = call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\"ui://t/view.html\"}}")
				.getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject();
		assertEquals(McpServer.UI_MIME, read.get("mimeType").getAsString());
		assertTrue(read.getAsJsonObject("_meta").getAsJsonObject("ui").get("prefersBorder").getAsBoolean());
	}

	@Test
	void knowsWhetherTheClientRendersViews() {
		s.singleClient(true);
		assertNull(s.uiClient(), "unknown until the client says");
		call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"capabilities\":{\"extensions\":"
				+ "{\"io.modelcontextprotocol/ui\":{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}}}}}");
		assertEquals(Boolean.TRUE, s.uiClient());
		call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"capabilities\":{}}}");
		assertEquals(Boolean.FALSE, s.uiClient());
		assertFalse(McpServer.rendersViews(JsonParser.parseString("{\"capabilities\":{\"extensions\":{\"io.modelcontextprotocol/ui\":"
				+ "{\"mimeTypes\":[\"text/plain\"]}}}}").getAsJsonObject()), "only the HTML view profile counts");
	}

	@Test
	void serveLoopWritesOneLinePerResponseOnly() throws Exception {
		String in = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
				+ "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
				+ "\n"
				+ "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"length\":\"1 m\"}}}\n";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		s.serve(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, true, StandardCharsets.UTF_8));
		String[] lines = out.toString(StandardCharsets.UTF_8).strip().split("\n");
		assertEquals(2, lines.length, "one response per request, none for notifications or blank lines");
		for (String l : lines) {
			assertTrue(JsonParser.parseString(l).getAsJsonObject().has("result"));
		}
	}
}
