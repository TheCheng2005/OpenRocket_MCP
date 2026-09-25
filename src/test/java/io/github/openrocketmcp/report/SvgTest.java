package io.github.openrocketmcp.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;

class SvgTest {
	static void wellFormed(String svg) {
		assertDoesNotThrow(() -> DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))), svg);
		assertFalse(svg.contains("NaN"), "no NaN coordinates");
		assertFalse(svg.contains("Infinity"));
	}

	@Test
	void normalChartIsWellFormedAndEscaped() {
		double[] x = { 0, 1, 2, 3, 4 }, y = { 1.2, 1.8, 2.4, 2.2, 2.0 };
		String svg = Svg.line("Stability <ascent> & \"more\"", "Time (s)", "Stability (cal)", x, y, 1.5, "min 1.5 cal",
				List.of(new Svg.Marker(0.5, "Rail exit"), new Svg.Marker(0.5, "Same time"), new Svg.Marker(3.9, "Burnout")));
		wellFormed(svg);
		assertTrue(svg.contains("&lt;ascent&gt;"));
		assertTrue(svg.contains("prefers-color-scheme"), "dark mode");
	}

	@Test
	void degenerateDataStillRenders() {
		wellFormed(Svg.line("Empty", "t", "y", new double[0], new double[0], Double.NaN, null, List.of()));
		wellFormed(Svg.line("All missing", "t", "y", new double[] { 0, 1 }, new double[] { Double.NaN, Double.NaN }, 1.5, "ref", List.of()));
		wellFormed(Svg.line("Flat", "t", "y", new double[] { 0, 1, 2 }, new double[] { 2, 2, 2 }, Double.NaN, null, List.of()));
		wellFormed(Svg.line("Single", "t", "y", new double[] { 5 }, new double[] { 1 }, 1.5, "ref", List.of(new Svg.Marker(5, "m"))));
		wellFormed(Svg.line("Gaps", "t", "y", new double[] { 0, 1, 2, 3 }, new double[] { 1, Double.NaN, Double.POSITIVE_INFINITY, 2 },
				Double.NaN, null, List.of()));
	}

	@Test
	void ticksCoverRange() {
		for (double[] r : new double[][] { { 0, 1 }, { -3.2, 7.9 }, { 1.49, 1.51 }, { 0, 12000 } }) {
			double[] t = Svg.ticks(r[0], r[1]);
			assertTrue(t.length >= 2 && t.length <= 12, java.util.Arrays.toString(t));
			for (int i = 1; i < t.length; i++) {
				assertTrue(t[i] > t[i - 1]);
			}
		}
	}
}
