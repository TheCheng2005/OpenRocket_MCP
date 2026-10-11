package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;

class SuggestionsTest {
	static Set<String> registered(McpServer s) {
		JsonObject r = s.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}").getAsJsonObject("result");
		Set<String> out = new HashSet<>();
		for (JsonElement t : r.getAsJsonArray("tools")) {
			out.add(t.getAsJsonObject().get("name").getAsString());
		}
		return out;
	}

	@Test
	void everySuggestedToolExists() {
		Set<String> tools = registered(Main.build(new Context(Standards.defaults())));
		for (Suggestions.Feature f : Suggestions.FEATURES) {
			assertTrue(tools.contains(f.tool()), f.tool());
		}
		Suggestions.NEXT.forEach((from, to) -> {
			assertTrue(tools.contains(from), from);
			to.forEach(t -> assertTrue(tools.contains(t), from + " -> " + t));
			to.stream().filter(t -> !t.equals("capabilities"))
					.forEach(t -> assertTrue(Suggestions.FEATURES.stream().anyMatch(f -> f.tool().equals(t)), "no wording for " + t));
		});
	}

	@Test
	void eachSuggestionOnceAndNeverForWhatWasUsed() {
		Context ctx = new Context(Standards.defaults());
		ctx.toolsUsed.add("check_requirements");
		List<String> first = Suggestions.next(ctx, "run_simulation", Map.of("flight", Map.of("maxMach", "1.5")));
		assertEquals(2, first.size());
		assertFalse(first.toString().contains("check_requirements"), "used already");
		assertTrue(first.get(0).contains("monte_carlo"), first.toString());
		List<String> second = Suggestions.next(ctx, "run_simulation", Map.of("flight", Map.of("maxMach", "1.5")));
		assertTrue(second.stream().noneMatch(first::contains), "not twice: " + second);
		assertTrue(second.toString().contains("aero_heating"), "a fast flight gets the heating screen: " + second);
		Context slow = new Context(Standards.defaults());
		slow.suggested.addAll(List.of("check_requirements", "monte_carlo", "fin_flutter"));
		assertFalse(Suggestions.next(slow, "run_simulation", Map.of("flight", Map.of("maxMach", "0.4"))).toString()
				.contains("aero_heating"), "no heating screen for a subsonic rocket");
	}

	@Test
	void resultsCarryThemUnlessTheTeamTurnsThemOff() {
		McpServer s = Main.build(new Context(Standards.defaults()));
		String open = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"open_design\",\"arguments\":"
				+ "{\"example\":\"Dual parachute\"}}}";
		String text = s.handleLine(open).getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(JsonParser.parseString(text).getAsJsonObject().has("youCouldAlsoAsk"), text);

		McpServer quiet = Main.build(new Context(Standards.defaults().patched(JsonParser.parseString("{\"suggestions\":false}").getAsJsonObject())));
		text = quiet.handleLine(open).getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		assertFalse(JsonParser.parseString(text).getAsJsonObject().has("youCouldAlsoAsk"), text);
	}
}
