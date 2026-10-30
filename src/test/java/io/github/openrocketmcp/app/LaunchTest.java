package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a launch is given (designs from Finder, Explorer or a terminal) and where it sends them. */
class LaunchTest {
	@Test
	void designFilesAmongTheArguments(@TempDir Path dir) throws Exception {
		Path ork = Files.writeString(dir.resolve("Maple 10K.ork"), "x");
		Path rkt = Files.writeString(dir.resolve("old.RKT"), "x");
		List<String> files = App.designFiles(List.of("--workspace", dir.resolve("w.ork").toString(), "--no-browser", ork.toString(),
				rkt.toString(), dir.resolve("missing.ork").toString(), dir.resolve("notes.txt").toString()));
		assertEquals(List.of(ork.toString(), rkt.toString()), files, "option values, missing files and other types are not designs");
	}

	@Test
	void filesTravelInTheWindowAddress() {
		String url = App.withFiles("http://127.0.0.1:5000/#token=abc", List.of("/Users/team/Maple 10K.ork"));
		assertEquals("http://127.0.0.1:5000/#token=abc&open=%2FUsers%2Fteam%2FMaple+10K.ork", url);
	}

	@Test
	void oneAppPerDesignsFolder() {
		String a = Instance.key(Path.of("/Users/team/RocketMaxxing")), b = Instance.key(Path.of("/Users/team/Other"));
		assertNotEquals(a, b);
		assertEquals(a, Instance.key(Path.of("/Users/team/RocketMaxxing")));
		assertTrue(a.matches("[0-9a-f]{12}"), a);
	}
}
