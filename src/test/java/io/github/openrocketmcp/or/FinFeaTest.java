package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import io.github.openrocketmcp.standards.Standards;

/**
 * fin_fea: stress measures, mode labels and the deck; with CalculiX installed, the model against cantilever plate
 * theory (Poisson's ratio 0, so the plate bends exactly as a beam).
 */
class FinFeaTest {
	@Test
	void principalAndVonMisesStresses() {
		// The trigonometric cubic solution is accurate to ~1e-8 of the stress magnitude.
		double[] p = FinFea.principal(100, 0, 0, 0, 0, 0);
		Arrays.sort(p);
		assertEquals(0, p[0], 1e-5);
		assertEquals(100, p[2], 1e-5);
		// Pure shear tau: principal +-tau, von Mises sqrt(3) tau.
		p = FinFea.principal(0, 0, 0, 50, 0, 0);
		Arrays.sort(p);
		assertEquals(-50, p[0], 1e-5);
		assertEquals(50, p[2], 1e-5);
		assertEquals(Math.sqrt(3) * 50, FinFea.vonMises(0, 0, 0, 50, 0, 0), 1e-9);
		assertEquals(100, FinFea.vonMises(100, 0, 0, 0, 0, 0), 1e-9);
		// Invariants: the principal stresses of a general tensor sum to its trace.
		p = FinFea.principal(30, -20, 10, 15, -5, 8);
		assertEquals(20, p[0] + p[1] + p[2], 1e-6);
	}

	@Test
	void modeLabelsFollowTheEdges() {
		double[] rise = { 0, 0.1, 0.3, 0.6, 1 }, fall = { 0, -0.1, -0.3, -0.6, -1 }, sBend = { 0, 0.4, 0.2, -0.5, -1 };
		assertEquals("1st bending", FinFea.kind(rise, rise));
		assertEquals("1st torsion", FinFea.kind(rise, fall));
		assertEquals("2nd bending", FinFea.kind(sBend, sBend));
		assertEquals("mixed bending-torsion", FinFea.kind(rise, new double[] { 0, 0, 0, 0, 0.05 }));
	}

	@Test
	void deckHasTheMeshAndBoundaryConditions() {
		FinFea.Plate p = new FinFea.Plate(0.2, 0.08, 0.15, 0.1, 0.003, null);
		FinFea.Material m = new FinFea.Material(55e9, 4.5e9, 0.06, 1780, 450e6, false);
		FinFea.Deck d = FinFea.deck(p, m, 5000, 8, 6, 3);
		String t = d.text();
		int nodes = (2 * 8 + 1) * (2 * 6 + 1) - 8 * 6;
		assertTrue(t.contains("*NSET,NSET=NALL,GENERATE\n1," + nodes + ",1"), "8-node elements, no centre nodes");
		assertEquals(48, d.elements());
		assertTrue(t.contains("TYPE=S8R") && t.contains("ROOT,1,6,0") && t.contains("EALL,P,5.000000e+03") && t.contains("*FREQUENCY\n3"));
		assertTrue(t.contains("5.500000e+10,5.500000e+10,5.500000e+10,0.0600,0.0600,0.0600,4.500000e+09"), "engineering constants");
		assertEquals(2 * 6 + 1, d.leadingEdge().length);
		assertEquals(p.span() * (0.2 + 2 * 0.08) / (3 * 0.28), p.centroidSpan(), 1e-12);
	}

	@Test
	void matchesCantileverPlateTheory(@TempDir Path dir) throws Exception {
		String ccx = FinFea.findCcx(null);
		assumeTrue(ccx != null, "CalculiX (ccx) not installed");
		double span = 0.15, chord = 0.15, t = 0.003, e = 70e9, rho = 2700, pressure = 1000;
		FinFea.Plate p = new FinFea.Plate(chord, chord, span, 0, t, null);
		FinFea.Material m = new FinFea.Material(e, e / 2, 0, rho, 276e6, true);
		FinFea.Deck d = FinFea.deck(p, m, pressure, 12, 10, 3);
		Files.writeString(dir.resolve("plate.inp"), d.text());
		FinFea.Result r = FinFea.run(ccx, dir, "plate", d, 120);
		// Beam theory, per unit chord: tip deflection q L^4 / 8 EI, root stress 6 M / t^2 = 3 q L^2 / t^2,
		// first frequency (1.875^2 / 2 pi) sqrt(EI / (rho t L^4)).
		double defl = 1.5 * pressure * Math.pow(span, 4) / (e * t * t * t);
		double stress = 3 * pressure * span * span / (t * t);
		assertEquals(defl, r.maxDeflection(), defl * 0.01, "tip deflection");
		assertEquals(stress, r.principalAwayFromCorners(), stress * 0.03, "root bending stress");
		assertEquals(FinFea.bendingFrequency(p, m), r.modes().get(0).frequency(), FinFea.bendingFrequency(p, m) * 0.01, "1st mode");
		assertEquals("1st bending", r.modes().get(0).kind());
		assertTrue(r.modes().stream().anyMatch(x -> x.kind().contains("torsion")), r.modes().toString());
		// Stress map: every element gets a value, and a cantilever's stress grows toward the root.
		double[][] g = r.principalGrid();
		for (double[] col : g) {
			for (double v : col) {
				assertTrue(v > 0, "every element has a stress");
			}
			assertTrue(col[0] > col[col.length - 1] * 5, "root far above tip");
		}
		String svg = FinFea.stressSvg(d, g, "plate", "largest principal", 276e6 / 2);
		assertEquals(1 + 50, svg.split("<rect").length - 1, "background and the colour scale");
		assertEquals(12 * 10, svg.split("<polygon").length - 1);
	}

	@Test
	void flightLoadIsOnTheMostLoadedFin() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Simulation sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults(), false);
		Sims.run(sim);
		FinSet f = StudiesTest.first(d, FinSet.class);
		FinFea.Load gust = FinFea.designLoad(sim, f, 8.33);
		FinFea.Load calm = FinFea.designLoad(sim, f, 0.1);
		assertTrue(gust.finForce() > 0 && gust.q() > 0 && gust.alpha() > 0, gust.toString());
		assertTrue(gust.finForce() >= calm.finForce(), "a stronger gust never lowers the design load");
		// Linear in angle of attack at the same flight point (both gust cases at max q).
		FinFea.Load gust2 = FinFea.designLoad(sim, f, 4.0);
		if (gust2.basis().equals(gust.basis()) && gust.basis().startsWith("crosswind")) {
			double perRad = gust.finForce() / gust.alpha();
			assertEquals(perRad, gust2.finForce() / gust2.alpha(), perRad * 1e-6);
		}
	}
}
