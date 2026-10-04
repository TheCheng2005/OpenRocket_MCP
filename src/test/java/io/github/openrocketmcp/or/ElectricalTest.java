package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Units;

/** Power and radio link budgets against hand calculations. */
class ElectricalTest {
	static final Electrical.Pyro PYRO = new Electrical.Pyro(1.6, 1.0, 0.3, 2);

	@Test
	void freeSpaceLossMatchesTheTextbook() {
		// FSPL = 20 log10(d_km) + 20 log10(f_MHz) + 32.44 dB: 1 km at 915 MHz = 91.67 dB.
		assertEquals(20 * Math.log10(915) + 32.44, Electrical.fspl(1000, 915e6), 0.01, "textbook constant rounded to 32.44");
		assertEquals(6.02, Electrical.fspl(2000, 915e6) - Electrical.fspl(1000, 915e6), 0.01, "6 dB per doubling");
	}

	@Test
	void onTheGroundTheLossGrowsFortyDbPerDecade() {
		Electrical.Radio r = new Electrical.Radio(915e6, 20, 2.15, 2.15, -123, 3, 10, 2, 0.1);
		double a = Electrical.groundLoss(1000, r), b = Electrical.groundLoss(10000, r);
		assertEquals(40, b - a, 0.01, "plane-earth: 40 dB per decade");
		assertEquals(40 * Math.log10(1000) - 20 * Math.log10(2) - 20 * Math.log10(0.1), a, 1e-9);
		assertTrue(a > Electrical.fspl(1000, 915e6), "worse than free space far out");
		double range = Electrical.groundRange(r);
		assertEquals(r.requiredMargin(), r.budget() - Electrical.groundLoss(range, r), 0.01, "range ends at the margin");
	}

	@Test
	void capacityFiringCurrentAndSag() {
		Electrical.Durations d = new Electrical.Durations(3600, 100, 3500); // 2 h
		Electrical.Circuit ok = new Electrical.Circuit("lipo", "", 7.4, 0.3, 0.15, 0.012, 2, 4);
		Electrical.Circuit weak = new Electrical.Circuit("9v", "", 9, 0.1, 3, 0.040, 1, 5);
		List<Map<String, Object>> rows = Electrical.power(List.of(ok, weak), PYRO, d, 0.8);
		assertEquals("PASS", rows.get(0).get("status"));
		assertTrue(rows.get(0).get("pyroCurrent").toString().startsWith(Units.num(7.4 / (0.15 + 1.6 + 0.3))), rows.get(0).toString());
		assertEquals("FAIL", rows.get(1).get("status"));
		String issues = rows.get(1).get("issues").toString();
		// 40 mA x 2 h = 80 mAh = all of 100 mAh x 0.8: flat; 9 / 4.9 = 1.84 A < 2 A; 9 - 1.84 x 3 = 3.49 V < 5 V.
		assertTrue(issues.contains("all-fire") && issues.contains("brownout"), issues);
	}

	@Test
	void theTeamDefaultsParse() {
		var pw = Standards.defaults().data().getAsJsonObject("electronics").getAsJsonObject("power");
		assertEquals(3, Electrical.circuits(pw.getAsJsonArray("circuits")).size());
	}

}
