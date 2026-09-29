package io.github.openrocketmcp.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.or.Designs;

/** Our SVGs rasterize to real images (CSS classes, text, arcs and transparency included). */
class PngTest {
	static BufferedImage decode(byte[] png) throws Exception {
		assertEquals((byte) 0x89, png[0]);
		assertEquals('P', png[1]);
		return ImageIO.read(new ByteArrayInputStream(png));
	}

	static int colours(BufferedImage im) {
		Set<Integer> seen = new HashSet<>();
		for (int y = 0; y < im.getHeight(); y += 3) {
			for (int x = 0; x < im.getWidth(); x += 3) {
				seen.add(im.getRGB(x, y) & 0xF0F0F0);
			}
		}
		return seen.size();
	}

	@Test
	void drawingRendersAtItsOwnWidthWithItsColours() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		String svg = Drawing.svg(fc, "Dual parachute");
		BufferedImage im = decode(Png.render(svg, Png.widthOf(svg)));
		assertEquals(Png.widthOf(svg), im.getWidth());
		assertTrue(im.getHeight() > 100);
		assertTrue(colours(im) > 12, "fills, outlines, legend and text are drawn, not a blank canvas: " + colours(im));
		// The background comes from a CSS class (.bg{fill:#fcfcfb}).
		assertEquals(0xfcfcfb, im.getRGB(2, 2) & 0xFFFFFF);
	}

	@Test
	void plotsRender() throws Exception {
		String svg = Svg.lines("Altitude", "Time (s)", "Altitude (m)",
				List.of(new Svg.Series("simulated", new double[] { 0, 1, 2, 3 }, new double[] { 0, 50, 80, 90 })), Double.NaN, null,
				List.of());
		BufferedImage im = decode(Png.render(svg, 800));
		assertEquals(800, im.getWidth());
		assertTrue(colours(im) > 4);
	}

	@Test
	void attachingOutsideAToolCallIsHarmless() {
		assertTrue(Png.attach("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>",
				"x"), "renders; with no call in progress the image is simply not sent");
		assertTrue(!Png.attach("not svg", "x"), "a bad document is reported, not thrown");
	}
}
