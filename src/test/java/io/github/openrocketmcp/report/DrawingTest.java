package io.github.openrocketmcp.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import io.github.openrocketmcp.or.Designs;

class DrawingTest {
	@Test
	void outlinesFollowTheGeometry() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		int bodies = 0, fins = 0;
		for (RocketComponent c : fc.getActiveComponents()) {
			if (c instanceof SymmetricComponent s && s.getLength() > 0) {
				bodies++;
			} else if (c instanceof FinSet) {
				fins++;
			}
		}
		var shapes = Drawing.outlines(fc);
		assertEquals(bodies + 2 * fins, shapes.size());
		double xmax = 0;
		for (var s : shapes) {
			for (double[] p : s.pts()) {
				xmax = Math.max(xmax, p[0]);
			}
		}
		assertEquals(fc.getLength(), xmax, 0.02 * fc.getLength(), "drawing spans the vehicle length");
		String svg = Drawing.svg(fc, "Two stage <test>");
		SvgTest.wellFormed(svg);
		assertTrue(svg.contains("CG ") && svg.contains("CP "));
	}

	@Test
	void podsAreDrawnOffAxis() throws Exception {
		Designs.Design d = new Designs().openExample("Parallel booster");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		boolean offAxis = false;
		for (var s : Drawing.outlines(fc)) {
			if (!s.fin()) {
				double ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
				for (double[] p : s.pts()) {
					ymin = Math.min(ymin, p[1]);
					ymax = Math.max(ymax, p[1]);
				}
				offAxis |= Math.abs(ymin + ymax) > 0.01; // not symmetric about the axis
			}
		}
		assertTrue(offAxis, "parallel boosters appear beside the core");
		SvgTest.wellFormed(Drawing.svg(fc, "pods"));
	}
}
