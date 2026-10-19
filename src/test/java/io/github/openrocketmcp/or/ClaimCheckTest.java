package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;

/** Values read the way people write them in notes. */
class ClaimCheckTest {
	@Test
	void readsValuesAsWritten() {
		assertEquals(30000 * 0.3048, ClaimCheck.parse("~30k ft", Dim.DISTANCE).si(), 1e-6);
		assertEquals("~", ClaimCheck.parse("~30k ft", Dim.DISTANCE).op());
		assertEquals(">=", ClaimCheck.parse("at least 2 cal", null).op());
		assertEquals(2, ClaimCheck.parse("at least 2 cal", null).si(), 1e-12);
		assertEquals("<", ClaimCheck.parse("under 25 lb", Dim.MASS).op());
		assertEquals(25 * 0.45359237, ClaimCheck.parse("under 25 lb", Dim.MASS).si(), 1e-9);
		assertEquals(1.3, ClaimCheck.parse("Mach 1.3", null).si(), 1e-12);
		assertEquals(0.0254 / 8, ClaimCheck.parse("1/8 in", Dim.LENGTH).si(), 1e-12);
		assertEquals(">=", ClaimCheck.parse("≥ 100 ft/s", Dim.VELOCITY).op());
		assertEquals(9144, ClaimCheck.parse("9,144 m", Dim.DISTANCE).si(), 1e-9);
		assertThrows(ToolException.class, () -> ClaimCheck.parse("30000", Dim.DISTANCE), "no unit");
		assertEquals(0.05, ClaimCheck.tolerance("5%", Dim.DISTANCE)[0], 1e-12);
		assertEquals(60.96, ClaimCheck.tolerance("200 ft", Dim.DISTANCE)[1], 1e-9);
	}

	@Test
	void namesPeopleUse() {
		assertEquals("railExitVelocity", ProjectFacts.key("Rail exit"));
		assertEquals("burnoutMass", ProjectFacts.key("dry mass"));
		assertEquals("mainDescentRate", ProjectFacts.key("descent rate"));
		assertEquals("maxMach", ProjectFacts.key("Mach"));
		assertEquals("apogee", ProjectFacts.key("apogee"));
		assertEquals(null, ProjectFacts.key("warp factor"));
	}
}
