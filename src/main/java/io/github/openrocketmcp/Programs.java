package io.github.openrocketmcp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the outside programs the engine can use (ffmpeg, CalculiX, Claude Code, Codex). An app started from the Dock,
 * Finder or the Start menu does not get the terminal's PATH (on macOS it is only /usr/bin:/bin:/usr/sbin:/sbin), so
 * the places package managers install to are searched after PATH: Homebrew, MacPorts, conda, npm, winget, Scoop...
 */
public final class Programs {
	private Programs() {
	}

	public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	/** The first of {@code names} found (with .exe or .cmd on Windows), or null. */
	public static Path find(String... names) {
		for (Path dir : dirs()) {
			for (String name : names) {
				for (String ext : WINDOWS ? new String[] { ".exe", ".cmd" } : new String[] { "" }) {
					Path p = dir.resolve(name + ext);
					if (Files.isRegularFile(p) && (WINDOWS || Files.isExecutable(p))) {
						return p;
					}
				}
			}
		}
		return null;
	}

	/** PATH, then the usual install folders for this system. */
	public static List<Path> dirs() {
		List<Path> dirs = new ArrayList<>();
		String path = System.getenv("PATH");
		if (path == null) {
			path = System.getenv("Path");
		}
		if (path != null) {
			for (String d : path.split(File.pathSeparator)) {
				if (!d.isBlank()) {
					add(dirs, d);
				}
			}
		}
		String home = System.getProperty("user.home");
		for (String d : new String[] { ".local/bin", ".claude/local", ".npm-global/bin", ".volta/bin", ".bun/bin", ".cargo/bin",
				"miniforge3/bin", "miniconda3/bin", "anaconda3/bin", "mambaforge/bin" }) {
			add(dirs, Path.of(home, d).toString());
		}
		if (WINDOWS) {
			String appData = System.getenv("APPDATA"), local = System.getenv("LOCALAPPDATA"), data = System.getenv("ProgramData");
			if (appData != null) {
				add(dirs, Path.of(appData, "npm").toString());
			}
			if (local != null) {
				add(dirs, Path.of(local, "Microsoft", "WinGet", "Links").toString());
			}
			if (data != null) {
				add(dirs, Path.of(data, "chocolatey", "bin").toString());
			}
			add(dirs, Path.of(home, "scoop", "shims").toString());
			for (String d : new String[] { "miniforge3/Library/bin", "miniconda3/Library/bin", "anaconda3/Library/bin" }) {
				add(dirs, Path.of(home, d).toString());
			}
		} else {
			for (String d : new String[] { "/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "/usr/bin", "/snap/bin" }) {
				add(dirs, d);
			}
		}
		return dirs;
	}

	private static void add(List<Path> dirs, String d) {
		try {
			Path p = Path.of(d);
			if (!dirs.contains(p)) {
				dirs.add(p);
			}
		} catch (RuntimeException ignored) {
			// a PATH entry that is not a path on this system
		}
	}
}
