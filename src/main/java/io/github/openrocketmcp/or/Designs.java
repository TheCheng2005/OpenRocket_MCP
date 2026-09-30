package io.github.openrocketmcp.or;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.file.RocketLoadException;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.mcp.ToolException;

/**
 * Open OpenRocket documents, addressed by short ids ("d1", "d2", ...). Edits stay in memory until saved.
 */
public final class Designs {

	public static final String[] EXAMPLES = {
			"A simple model rocket", "Two stage high power rocket", "Dual parachute deployment",
			"Airstart timing", "Clustered motors", "Three stage low power rocket", "Parallel booster staging",
			"Chute release", "Deployable payload", "ARC payload rocket", "Tube fin rocket", "3D printable nose cone and fins",
			"Base drag hack (short-wide)", "Pods--airframes and winglets", "Pods--powered with recovery deployment" };

	public static final class Design {
		public final String id;
		public final OpenRocketDocument doc;
		public Path path;
		public final String origin;
		/** Edits made in this session, for undo / redo. */
		public final History history = new History();
		/** What OpenRocket reported while reading the file (older versions, RockSim imports); empty for most files. */
		public final List<String> loadWarnings = new ArrayList<>();
		/** The file it was imported from when that is not an .ork (a RockSim .rkt); it is saved as .ork elsewhere. */
		public Path importedFrom;

		Design(String id, OpenRocketDocument doc, Path path, String origin) {
			this.id = id;
			this.doc = doc;
			this.path = path;
			this.origin = origin;
		}

		public String name() {
			return doc.getRocket().getName();
		}
	}

	private final Map<String, Design> designs = new LinkedHashMap<>();
	private int counter;

	public synchronized Design open(Path path) throws Exception {
		OrRuntime.init();
		Path p = path.toAbsolutePath().normalize();
		if (!Files.exists(p)) {
			throw new ToolException("File not found: " + p);
		}
		GeneralRocketLoader loader = new GeneralRocketLoader(p.toFile());
		OpenRocketDocument doc;
		try {
			doc = loader.load();
		} catch (RocketLoadException e) {
			throw new ToolException("OpenRocket could not read " + p.getFileName() + ": " + e.getMessage()
					+ ". It reads .ork files from any OpenRocket version and RockSim .rkt files.", e);
		}
		boolean ork = p.toString().toLowerCase(Locale.ROOT).endsWith(".ork");
		Design d;
		if (ork) {
			doc.setFile(p.toFile());
			doc.setSaved(true);
			d = register(doc, p, "file");
			loadAeroTable(d);
		} else {
			// An import (RockSim): never written back over the original; save_design writes an .ork beside it.
			doc.setSaved(false);
			d = register(doc, null, "import:" + p.getFileName());
			d.importedFrom = p;
		}
		for (Warning w : loader.getWarnings()) {
			d.loadWarnings.add(w.toString());
		}
		return d;
	}

	public synchronized Design openExample(String name) throws Exception {
		OrRuntime.init();
		String match = null;
		for (String e : EXAMPLES) {
			if (e.equalsIgnoreCase(name) || e.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
				match = e;
				break;
			}
		}
		if (match == null) {
			throw new ToolException("Unknown example '" + name + "'. Available: " + String.join(", ", EXAMPLES));
		}
		Path tmp = Files.createTempFile("openrocket-example-", ".ork");
		try (InputStream in = Designs.class.getResourceAsStream("/datafiles/examples/" + match + ".ork")) {
			if (in == null) {
				throw new ToolException("Example not bundled with this OpenRocket version: " + match);
			}
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
		}
		OpenRocketDocument doc = new GeneralRocketLoader(tmp.toFile()).load();
		tmp.toFile().deleteOnExit();
		return register(doc, null, "example:" + match);
	}

	public synchronized Design create(String name) {
		OrRuntime.init();
		OpenRocketDocument doc = OpenRocketDocumentFactory.createNewRocket();
		if (name != null && !name.isBlank()) {
			doc.getRocket().setName(name);
		}
		return register(doc, null, "new");
	}

	private Design register(OpenRocketDocument doc, Path path, String origin) {
		String id = "d" + (++counter);
		Design d = new Design(id, doc, path, origin);
		for (FlightConfiguration fc : doc.getRocket().getFlightConfigurations()) {
			Analysis.settle(fc);
		}
		designs.put(id, d);
		return d;
	}

	/** An imported RASAero / aero table lives next to the design (rocket.aero.json) and comes back on open. */
	static void loadAeroTable(Design d) throws IOException {
		Path f = AeroTable.sidecar(d.path);
		if (Files.exists(f)) {
			AeroTable.set(d.doc.getRocket(), AeroTable.fromJson(Files.readString(f)));
		}
	}

	static void saveAeroTable(Design d) throws IOException {
		Path f = AeroTable.sidecar(d.path);
		AeroTable.Table t = AeroTable.of(d.doc.getRocket());
		if (t != null) {
			Files.writeString(f, AeroTable.toJson(t));
		} else {
			Files.deleteIfExists(f); // the table was cleared since the design was opened
		}
	}

	/** Resolves a design id; when omitted and exactly one design is open, returns that one. */
	public synchronized Design get(String id) {
		if (id == null || id.isBlank()) {
			if (designs.size() == 1) {
				return designs.values().iterator().next();
			}
			throw new ToolException(designs.isEmpty()
					? "No design is open. Use open_design first."
					: "Several designs are open; pass designId (one of " + designs.keySet() + ").");
		}
		Design d = designs.get(id);
		if (d == null) {
			throw new ToolException("Unknown designId '" + id + "'. Open designs: " + designs.keySet());
		}
		return d;
	}

	public synchronized List<Design> all() {
		return new ArrayList<>(designs.values());
	}

	public synchronized void close(String id) {
		Design d = designs.remove(id);
		if (d == null) {
			throw new ToolException("Unknown designId '" + id + "'.");
		}
		// Its imported aero table is kept by rocket id: drop it unless another open copy of the file still uses it.
		String rid = d.doc.getRocket().getID().toString();
		if (designs.values().stream().noneMatch(o -> o.doc.getRocket().getID().toString().equals(rid))) {
			AeroTable.clear(d.doc.getRocket());
		}
	}

	public synchronized Path save(Design d, Path target) throws Exception {
		Path p = target != null ? target.toAbsolutePath().normalize() : d.path;
		if (p == null) {
			throw new ToolException(d.importedFrom != null
					? "This design was imported from " + d.importedFrom.getFileName() + "; pass a path ending in .ork, e.g. \""
							+ d.importedFrom.getFileName().toString().replaceAll("\\.[^.]+$", "") + ".ork\"."
					: "This design has no file yet; pass a path ending in .ork.");
		}
		if (!p.toString().toLowerCase(Locale.ROOT).endsWith(".ork")) {
			throw new ToolException("Save path must end in .ork");
		}
		if (p.getParent() != null) {
			Files.createDirectories(p.getParent());
		}
		try {
			new GeneralRocketSaver().save(p.toFile(), d.doc);
		} catch (IOException e) {
			throw new ToolException("Could not save to " + p + ": " + e.getMessage(), e);
		}
		d.doc.setFile(p.toFile());
		d.doc.setSaved(true);
		d.path = p;
		saveAeroTable(d);
		return p;
	}
}
