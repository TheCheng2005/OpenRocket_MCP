package io.github.openrocketmcp.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.or.Designs;

class View3dTest {
	/** Two boxes on the view axis: the nearer one hides the farther one, whatever order they are drawn in. */
	@Test
	void depthBufferKeepsTheNearerSurface() {
		Raster3d.Camera cam = Raster3d.Camera.lookAt(new double[] { 0, -10, 0 }, new double[] { 0, 0, 0 }, 30);
		Raster3d r = new Raster3d(64, 64, 2, cam, new double[] { 0, -1, 0 });
		r.clear(0x000000);
		Raster3d.Mesh near = new Raster3d.Mesh(FlightAnimation.box(new double[] { -1, -3, -1 }, new double[] { 1, -2, 1 }), 0xff0000);
		Raster3d.Mesh far = new Raster3d.Mesh(FlightAnimation.box(new double[] { -2, 2, -2 }, new double[] { 2, 3, 2 }), 0x0000ff);
		r.draw(Raster3d.Placed.at(near, 0, 0, 0));
		r.draw(Raster3d.Placed.at(far, 0, 0, 0));
		BufferedImage img = r.image();
		int centre = img.getRGB(32, 32), edge = img.getRGB(32, 16);
		assertTrue((centre >> 16 & 255) > (centre & 255) + 100, "red box in front: " + Integer.toHexString(centre));
		assertTrue((edge & 255) > 100, "blue box around it: " + Integer.toHexString(edge));
	}

	@Test
	void explodedViewLaysOutEveryPartWithItsMass() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		View3d.Result exploded = View3d.render(fc, "Dual parachute", View3d.Mode.EXPLODED, 25, 22, 1200);
		View3d.Result assembled = View3d.render(fc, "Dual parachute", View3d.Mode.ASSEMBLED, 25, 22, 1200);
		View3d.Result cut = View3d.render(fc, "Dual parachute", View3d.Mode.CUTAWAY, 25, 22, 1200);
		assertEquals(1200, exploded.image().getWidth());
		assertTrue(exploded.image().getHeight() > assembled.image().getHeight(), "internal parts laid out below the airframe");
		assertTrue(exploded.parts().size() > assembled.parts().size(), "the exploded list includes the parts inside");
		assertEquals(exploded.parts().size(), cut.parts().size());
		List<Object> kinds = new ArrayList<>();
		for (Map<String, Object> p : exploded.parts()) {
			kinds.add(p.get("kind"));
			assertTrue(p.get("mass").toString().matches(".*\\d.*(g|kg|oz|lb).*"), p.toString());
		}
		assertTrue(kinds.contains("nose cone") && kinds.contains("fin set") && kinds.contains("motor")
				&& kinds.contains("parachute / streamer"), kinds.toString());
	}

	@Test
	void explodedPiecesMoveApartAlongTheAxis() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		List<Model3d.Part> parts = Model3d.build(d.doc.getRocket().getSelectedConfiguration(), 24);
		View3d.layOut(parts, 0.1, 0.03, 0.001, new ArrayList<>());
		for (Model3d.Part p : parts) {
			assertEquals(p.piece * 0.1, p.shift[0], 1e-12, p.name);
			if (p.internal) {
				assertTrue(p.shift[2] < -0.03, p.name + " sits below the airframe");
			}
		}
	}

	@Test
	void cutawayDropsTheNearHalf() {
		List<double[]> tris = new ArrayList<>();
		tris.add(new double[] { 0, -1, 0, 1, -1, 0, 0, -1, 0.1 }); // near side (toward -y)
		tris.add(new double[] { 0, 1, 0, 1, 1, 0, 0, 1, 0.1 }); // far side
		List<double[]> kept = View3d.cutNearHalf(tris, new double[] { 0, -1, 0 });
		assertEquals(1, kept.size());
		assertTrue(kept.get(0)[1] > 0);
	}
}
