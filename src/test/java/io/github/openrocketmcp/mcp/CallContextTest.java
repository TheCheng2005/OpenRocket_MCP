package io.github.openrocketmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Progress notifications, cancellation, concurrent stdio requests and image results. */
class CallContextTest {
	static McpServer server(CountDownLatch started) {
		McpServer s = new McpServer("test", "0.0.1", null);
		s.tool(new ToolDef("count", "Count", "Counts to three.", Schema.object().build(), true, a -> {
			CallContext c = CallContext.current();
			c.expect(3, "steps");
			for (int i = 0; i < 3; i++) {
				c.advance();
			}
			return Map.of("done", 3);
		}));
		s.tool(new ToolDef("wait", "Wait", "Runs until cancelled (at most 10 s).", Schema.object().build(), true, a -> {
			started.countDown();
			for (int i = 0; i < 1000; i++) {
				CallContext.current().checkCancelled();
				Thread.sleep(10);
			}
			return Map.of("finished", true);
		}));
		s.tool(new ToolDef("picture", "Picture", "Returns an image.", Schema.object().build(), true, a -> {
			CallContext.current().attach(new CallContext.Image("image/png", "iVBORw0KGgo=", "a picture"));
			return Map.of("ok", true);
		}));
		return s;
	}

	static String call(int id, String tool, boolean token) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}"
				+ (token ? ",\"_meta\":{\"progressToken\":\"tok-" + id + "\"}" : "") + "}}";
	}

	@Test
	void progressIsSentOnlyWithAToken() {
		McpServer s = server(new CountDownLatch(1));
		List<JsonObject> notes = new ArrayList<>();
		JsonObject r = s.handle(call(1, "count", true), notes::add);
		assertFalse(r.getAsJsonObject("result").get("isError").getAsBoolean());
		assertFalse(notes.isEmpty());
		JsonObject last = notes.get(notes.size() - 1).getAsJsonObject("params");
		assertEquals("notifications/progress", notes.get(0).get("method").getAsString());
		assertEquals("tok-1", last.get("progressToken").getAsString());
		assertEquals(3, last.get("progress").getAsInt());
		assertEquals(3, last.get("total").getAsInt());
		assertEquals("3 of 3 steps", last.get("message").getAsString());
		notes.clear();
		s.handle(call(2, "count", false), notes::add);
		assertTrue(notes.isEmpty(), "no progressToken, no notifications");
	}

	@Test
	void imagesAreAddedToTheResult() {
		JsonObject r = server(new CountDownLatch(1)).handleLine(call(1, "picture", false)).getAsJsonObject("result");
		var content = r.getAsJsonArray("content");
		assertEquals(2, content.size());
		assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
		JsonObject img = content.get(1).getAsJsonObject();
		assertEquals("image", img.get("type").getAsString());
		assertEquals("image/png", img.get("mimeType").getAsString());
		assertEquals("iVBORw0KGgo=", img.get("data").getAsString());
	}

	@Test
	void stdioAnswersOtherRequestsWhileACallRunsAndCancelStopsIt() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		McpServer s = server(started);
		PipedOutputStream feed = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream(feed);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Thread serving = new Thread(() -> {
			try {
				s.serve(in, new PrintStream(out, true, StandardCharsets.UTF_8));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		serving.start();
		long t0 = System.nanoTime();
		feed.write((call(7, "wait", false) + "\n").getBytes(StandardCharsets.UTF_8));
		feed.flush();
		assertTrue(started.await(10, TimeUnit.SECONDS), "the long call started");
		feed.write("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
		feed.flush();
		for (int i = 0; i < 200 && !out.toString(StandardCharsets.UTF_8).contains("\"id\":8"); i++) {
			Thread.sleep(10);
		}
		assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"id\":8"), "ping answered while the call runs");
		feed.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":7,\"reason\":\"user\"}}\n"
				.getBytes(StandardCharsets.UTF_8));
		feed.close();
		serving.join(10_000);
		assertFalse(serving.isAlive());
		assertTrue((System.nanoTime() - t0) / 1e9 < 8, "the cancelled call stopped early");
		for (String line : out.toString(StandardCharsets.UTF_8).strip().split("\n")) {
			JsonObject m = JsonParser.parseString(line).getAsJsonObject();
			assertFalse(m.has("id") && m.get("id").getAsInt() == 7, "no response to a cancelled request: " + line);
		}
	}

	@Test
	void pipelinedRequestsAreAnsweredInOrder() throws Exception {
		McpServer s = server(new CountDownLatch(1));
		StringBuilder in = new StringBuilder();
		for (int i = 1; i <= 6; i++) {
			in.append(call(i, i % 2 == 0 ? "picture" : "count", false)).append('\n');
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		s.serve(new ByteArrayInputStream(in.toString().getBytes(StandardCharsets.UTF_8)),
				new PrintStream(out, true, StandardCharsets.UTF_8));
		String[] lines = out.toString(StandardCharsets.UTF_8).strip().split("\n");
		assertEquals(6, lines.length);
		for (int i = 0; i < 6; i++) {
			assertEquals(i + 1, JsonParser.parseString(lines[i]).getAsJsonObject().get("id").getAsInt(), "in the order sent");
		}
	}
}
