package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.Rocket;
import io.github.openrocketmcp.mcp.ToolException;

/** Components by id, full name, or a piece of a name only one part has. */
class ComponentLookupTest {
	@Test
	void aUniquePieceOfANameFindsThePart() throws Exception {
		Rocket r = new Designs().openExample("Dual parachute").doc.getRocket();
		assertEquals("Apex 12\" Drouge Parachute", Components.find(r, "Apex").getName());
		assertEquals("Apex 12\" Drouge Parachute", Components.find(r, "drouge").getName(), "any case");
		ToolException several = assertThrows(ToolException.class, () -> Components.find(r, "Parachute"));
		assertTrue(several.getMessage().contains("matches several components") && several.getMessage().contains("Apex"),
				several.getMessage());
		assertThrows(ToolException.class, () -> Components.find(r, "ap"), "two letters are too few to guess from");
		assertThrows(ToolException.class, () -> Components.find(r, "Nope"));
	}
}
