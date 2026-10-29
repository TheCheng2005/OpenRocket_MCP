package io.github.openrocketmcp.app;

import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import io.github.openrocketmcp.mcp.ToolException;

/** The system's own open and save dialogs (the app's page cannot see real file paths). */
final class FileDialogs {
	private FileDialogs() {
	}

	static Map<String, Object> choose(String mode, String name, java.nio.file.Path folder) throws Exception {
		if (GraphicsEnvironment.isHeadless()) {
			throw new ToolException("No file dialog here: type the file's path instead.");
		}
		boolean save = "save".equals(mode);
		AtomicReference<String> chosen = new AtomicReference<>();
		try {
			show(save, name, folder, chosen);
		} catch (Exception | LinkageError | java.awt.AWTError e) { // no screen, or no toolkit for it
			throw new ToolException("No file dialog here: type the file's path instead.");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("path", chosen.get());
		return out;
	}

	private static void show(boolean save, String name, java.nio.file.Path folder, AtomicReference<String> chosen) throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			Frame owner = new Frame();
			owner.setAlwaysOnTop(true);
			FileDialog fd = new FileDialog(owner, save ? "Save design" : "Open design", save ? FileDialog.SAVE : FileDialog.LOAD);
			fd.setDirectory(folder.toAbsolutePath().toString()); // the designs folder, like OpenRocket's last-used folder
			fd.setFilenameFilter((dir, n) -> n.toLowerCase().endsWith(".ork") || n.toLowerCase().endsWith(".rkt"));
			if (!save) {
				fd.setFile("*.ork");
			} else if (name != null) {
				fd.setFile(name);
			}
			fd.setVisible(true);
			if (fd.getFile() != null) {
				chosen.set(new File(fd.getDirectory(), fd.getFile()).getAbsolutePath());
			}
			owner.dispose();
		});
	}
}
