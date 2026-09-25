package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;

/** The workspace: the folder relative paths resolve against (the shared folder on a team server). */
public final class FileTools {
	private FileTools() {
	}

	static final int MAX = 300;

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("list_files", "List design and data files",
				"List files in the workspace (the folder relative paths are resolved against; on a team server, the shared "
						+ "folder everyone uploads to): designs (.ork), motor files (.eng/.rse), RASAero tables, flight logs, "
						+ "reports. Use it to find a design to open or the file someone uploaded.",
				Schema.object()
						.str("folder", "Sub-folder to list (default: the whole workspace).", false)
						.str("match", "Only names containing this text or ending in this extension, e.g. \".ork\".", false).build(),
				true, a -> {
					Path root = a.has("folder") ? ctx.path(a.str("folder")) : ctx.workspace();
					if (!Files.isDirectory(root)) {
						throw new ToolException("Not a folder: " + a.str("folder", ""));
					}
					String match = a.str("match", "").toLowerCase(Locale.ROOT);
					List<Map<String, Object>> files = new ArrayList<>();
					int[] total = { 0 };
					try (Stream<Path> w = Files.walk(root, 6)) {
						w.filter(p -> !hidden(root, p) && Files.isRegularFile(p))
								.filter(p -> match.isEmpty() || p.getFileName().toString().toLowerCase(Locale.ROOT).contains(match))
								.sorted().forEach(p -> {
									if (++total[0] <= MAX) {
										Map<String, Object> m = new LinkedHashMap<>();
										m.put("path", ctx.workspace().relativize(p).toString().replace('\\', '/'));
										try {
											m.put("size", Files.size(p) < 1024 ? Files.size(p) + " B"
													: String.format(Locale.ROOT, "%.0f KB", Files.size(p) / 1024.0));
											m.put("modified", Files.getLastModifiedTime(p).toString().substring(0, 16).replace('T', ' '));
										} catch (IOException ignored) {
											// listed without details
										}
										files.add(m);
									}
								});
					}
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("workspace", ctx.sandboxed() ? "shared team workspace" : ctx.workspace().toString());
					out.put("files", files);
					if (total[0] > MAX) {
						out.put("note", total[0] + " files; showing the first " + MAX + ". Narrow with folder or match.");
					}
					if (ctx.filesUrl() != null) {
						out.put("uploadAndDownload", ctx.filesUrl());
					}
					return out;
				}));
	}

	/** Skips dot-files and build / VCS / dependency folders. */
	static boolean hidden(Path root, Path p) {
		for (Path seg : root.relativize(p)) {
			String n = seg.toString();
			if (n.startsWith(".") || n.equals("build") || n.equals("node_modules") || n.equals("__pycache__")) {
				return true;
			}
		}
		return false;
	}
}
