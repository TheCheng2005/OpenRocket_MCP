package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.mcp.ToolException;

/** Reading product pages (constructed test pages in src/test/resources/products, see its README). */
class ProductsTest {
	static String page(String name) throws IOException {
		return Files.readString(Path.of("src/test/resources/products", name));
	}

	static Products.Product product(String url, String file) throws IOException {
		return Products.identify(Products.read(url, page(file)), null);
	}

	@AfterEach
	void network() {
		Web.use(null);
	}

	@Test
	void aParachuteFromStructuredDataWithTheCatalogFillingTheGaps() throws Exception {
		Products.Product p = product("https://shop.example.com/iris-84", "chute-jsonld.html");
		assertEquals(Products.Kind.PARACHUTE, p.kind());
		assertEquals("Fruity Chutes", p.page().brand());
		assertEquals(84 * 0.0254, p.values().get("diameter").si(), 1e-9);
		assertEquals("page", p.values().get("diameter").source());
		// The page gives no Cd, weight or packed size: the catalog's Iris Ultra 84 does.
		assertNotNull(p.preset());
		assertTrue(p.preset().getPartNo().startsWith("IFC-084"), p.preset().getPartNo());
		assertEquals(2.2, p.values().get("cd").si(), 1e-9);
		assertTrue(p.values().get("cd").source().startsWith("catalog"));
		assertTrue(p.values().containsKey("packedLength") && p.values().containsKey("mass"));
		// A "weight" in a script block is not read as the canopy's weight.
		assertTrue(p.values().get("mass").si() < 1);
	}

	@Test
	void aParachuteFromASpecTable() throws Exception {
		Products.Product p = product("https://www.the-rocketman.com/products/standard-60", "chute-table.html");
		assertEquals(Products.Kind.PARACHUTE, p.kind());
		assertEquals("Rocketman", p.page().brand(), "brand from the shop's address");
		assertEquals(60 * 0.0254, p.values().get("diameter").si(), 1e-9, "the 60 in title, not the 12 ft in 'also see'");
		assertEquals(0.97, p.values().get("cd").si(), 1e-9);
		assertEquals("page", p.values().get("cd").source());
		assertEquals(9.5 * 0.028349523125, p.values().get("mass").si(), 1e-9);
		assertEquals(3 * 0.0254, p.values().get("packedDiameter").si(), 1e-9);
		assertEquals(7 * 0.0254, p.values().get("packedLength").si(), 1e-9);
	}

	@Test
	void pastedTextWithoutHtml() {
		Products.Product p = Products.identify(Products.read(null,
				"Drogue Parachute 24 inch\nCoefficient of drag 1.5\nWeight 2.1 oz\nPacked 1.5 x 4 in"), null);
		assertEquals(Products.Kind.PARACHUTE, p.kind());
		assertEquals(0.6096, p.values().get("diameter").si(), 1e-9);
		assertEquals(1.5, p.values().get("cd").si(), 1e-9);
		// The maker named in pasted text finds the catalog entry.
		Products.Product q = Products.identify(Products.read(null, "Iris Ultra 84\" Standard Parachute\nBrand: Fruity Chutes"), null);
		assertEquals("Fruity Chutes", q.page().brand());
		assertEquals(2.2, q.values().get("cd").si(), 1e-9);
		Products.Product r = Products.identify(Products.read(null, "Fruity Chutes\nIris Ultra 84\" Compact Parachute"), null);
		assertTrue(r.preset().getPartNo().endsWith("-S"), "compact: " + r.preset().getPartNo());
	}

	@Test
	void motorsItemsAndCatalogParts() throws Exception {
		Products.Product m = product("https://shop.example.com/j350", "motor.html");
		assertEquals(Products.Kind.MOTOR, m.kind());
		assertEquals("J350W-L", m.motor());
		assertEquals("AeroTech", m.motorMaker());
		Products.Product tc = Products.identify(Products.read("https://www.thrustcurve.org/motors/AeroTech/K1000T/", ""), null);
		assertEquals(Products.Kind.MOTOR, tc.kind());
		assertEquals("K1000T", tc.motor());

		Products.Product a = product("https://shop.example.com/raven", "altimeter.html");
		assertEquals(Products.Kind.ITEM, a.kind());
		assertEquals(0.0064, a.values().get("mass").si(), 1e-9);
		assertEquals(1.8 * 0.0254, a.values().get("length").si(), 1e-9);

		Products.Product n = product("https://shop.example.com/pnc", "nosecone.html");
		assertEquals(Products.Kind.PART, n.kind());
		assertEquals("PNC-3.90", n.preset().getPartNo());
	}

	@Test
	void onlyPublicPagesAreFetched() {
		for (String bad : new String[] { "http://127.0.0.1/admin", "http://localhost:8080/x", "http://10.0.0.5/", "http://192.168.1.1/",
				"http://169.254.169.254/latest/meta-data", "http://[::1]/", "file:///etc/passwd", "ftp://example.com/x" }) {
			assertThrows(ToolException.class, () -> Web.get(bad), bad);
		}
		// A redirect to a private address is refused too.
		Web.use(new Web.Client() {
			@Override
			public Web.Response get(URI uri) {
				return new Web.Response(302, "http://127.0.0.1/secret", "");
			}

			@Override
			public Web.Response postJson(URI uri, String json) {
				throw new AssertionError();
			}
		});
		String why = assertThrows(ToolException.class, () -> Web.get("https://93.184.216.34/page")).getMessage();
		assertTrue(why.contains("127.0.0.1"), why);
	}

	@Test
	void aShopThatBlocksRobotsSaysToPasteThePage() {
		Web.use(new Web.Client() {
			@Override
			public Web.Response get(URI uri) {
				return new Web.Response(403, null, "Access denied");
			}

			@Override
			public Web.Response postJson(URI uri, String json) {
				return get(uri);
			}
		});
		String why = assertThrows(ToolException.class, () -> Web.get("https://93.184.216.34/chute")).getMessage();
		assertTrue(why.contains("pageText"), why);
	}

	@Test
	void aMotorNewerThanTheDatabaseComesFromThrustCurve(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
		String eng = "; test motor\nK777X 54 400 P 1.2 2.4 Test\n0.1 900\n1.0 800\n1.5 0\n;\n";
		Web.use(new Web.Client() {
			@Override
			public Web.Response get(URI uri) {
				throw new AssertionError();
			}

			@Override
			public Web.Response postJson(URI uri, String json) {
				if (uri.getPath().endsWith("search.json")) {
					assertTrue(json.contains("K777X"), json);
					return new Web.Response(200, null, "{\"results\":[{\"motorId\":\"abc123\",\"designation\":\"K777X\"}]}");
				}
				assertTrue(json.contains("abc123") && json.contains("RASP"), json);
				return new Web.Response(200, null, "{\"results\":[{\"motorId\":\"abc123\",\"format\":\"RASP\",\"data\":\""
						+ java.util.Base64.getEncoder().encodeToString(eng.getBytes()) + "\"}]}");
			}
		});
		var m = Motors.fromThrustCurve("Test", "K777X", dir);
		assertEquals("K777X", m.getDesignation());
		assertTrue(Files.exists(dir.resolve("K777X.eng")));
		assertEquals(m, Motors.find("K777X", null), "usable by set_motor afterwards");
	}
}
