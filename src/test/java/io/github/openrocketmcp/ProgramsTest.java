package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Outside programs are found where package managers put them, not only on the terminal's PATH. */
class ProgramsTest {
	@Test
	void searchesPastThePath() {
		List<Path> dirs = Programs.dirs();
		String home = System.getProperty("user.home");
		assertTrue(dirs.contains(Path.of(home, ".local/bin")), dirs.toString());
		assertTrue(dirs.contains(Path.of(home, "miniforge3/bin")), "conda, where the FEA message says CalculiX can come from");
		if (Programs.WINDOWS) {
			assertTrue(dirs.contains(Path.of(home, "scoop", "shims")), dirs.toString());
		} else {
			assertTrue(dirs.contains(Path.of("/opt/homebrew/bin")) && dirs.contains(Path.of("/usr/local/bin")), "Homebrew");
		}
		assertEquals(dirs.size(), dirs.stream().distinct().count(), "no folder twice");
	}

	@Test
	void findsWhatIsThereOnly() {
		assertNull(Programs.find("surely-not-a-program-name-1234"));
		if (!Programs.WINDOWS) {
			assertTrue(Programs.find("sh") != null, "sh is in /bin or /usr/bin");
		}
	}
}
