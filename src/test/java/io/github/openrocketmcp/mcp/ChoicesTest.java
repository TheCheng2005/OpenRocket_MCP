package io.github.openrocketmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import io.github.openrocketmcp.units.Dim;

/** Arguments limited to a list of values: forgiving spelling, a clear error naming the choices. */
class ChoicesTest {
	static final JsonObject SCHEMA = Schema.object()
			.enumStr("objective", "What to optimize.", true, "max_apogee", "min_mass", "target_apogee").build();

	@Test
	void spellingIsForgivenAndRewritten() {
		JsonObject a = new JsonObject();
		a.addProperty("objective", "Max apogee");
		assertNull(McpServer.normalizeChoices(SCHEMA, a));
		assertEquals("max_apogee", a.get("objective").getAsString());
		a.addProperty("objective", "MIN-MASS");
		assertNull(McpServer.normalizeChoices(SCHEMA, a));
		assertEquals("min_mass", a.get("objective").getAsString());
	}

	@Test
	void anUnknownArgumentIsRefusedWithTheClosestName() {
		JsonObject schema = Schema.object().str("minClass", "", false).str("maxClass", "", false).qty("maxLength", "", false).build();
		JsonObject a = new JsonObject();
		a.addProperty("maxClas", "M");
		String err = McpServer.unknownArguments("search_motors", schema, a);
		assertTrue(err.contains("search_motors has no argument 'maxClas' (did you mean 'maxClass'?)")
				&& err.contains("It takes: minClass, maxClass, maxLength"), err);
		a = new JsonObject();
		a.addProperty("impulseClass", "Q");
		err = McpServer.unknownArguments("search_motors", schema, a);
		assertTrue(err.contains("'impulseClass'") && !err.contains("did you mean"), err);
		a = new JsonObject();
		a.addProperty("maxClass", "M");
		a.addProperty("designId", "d1");
		assertNull(McpServer.unknownArguments("search_motors", schema, a), "a stray designId is let through");
		a.addProperty("x", 1);
		assertTrue(McpServer.unknownArguments("list_designs", Schema.object().build(), a).endsWith("It takes no arguments."));
	}

	@Test
	void quantitiesThatMustBePositive() {
		JsonObject a = new JsonObject();
		a.addProperty("rate", "-20 ft/s");
		ToolException e = assertThrows(ToolException.class, () -> new Args(a).positive("rate", Dim.VELOCITY));
		assertTrue(e.getMessage().contains("'rate' must be greater than zero"), e.getMessage());
		a.addProperty("rate", "20 ft/s");
		assertEquals(6.096, new Args(a).positive("rate", Dim.VELOCITY), 1e-9);
	}

	@Test
	void anUnknownValueNamesTheChoices() {
		JsonObject a = new JsonObject();
		a.addProperty("objective", "maximize altitude");
		String err = McpServer.normalizeChoices(SCHEMA, a);
		assertTrue(err.contains("'maximize altitude' is not a valid objective") && err.contains("max_apogee, min_mass, target_apogee"),
				err);
		assertNull(McpServer.normalizeChoices(SCHEMA, new JsonObject()), "absent optional values are fine");
	}
}
