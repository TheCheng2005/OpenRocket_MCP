package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.MassComponent;
import io.github.openrocketmcp.standards.Standards;

/** compare_designs: two revisions of a design. */
class DiffTest {
	@TempDir
	Path tmp;

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> list(Map<String, Object> out, String key) {
		return (List<Map<String, Object>>) out.get(key);
	}

	static Map<String, Object> row(Map<String, Object> out, String group, String quantity) {
		return list(out, group).stream().filter(r -> quantity.equals(r.get("quantity"))).findFirst().orElseThrow();
	}

	@Test
	void aSavedCopyHasNoDifferences() throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("Dual parachute");
		Path v1 = designs.save(d, tmp.resolve("v1.ork"));
		Designs.Design base = Diff.load(v1, "file");
		Map<String, Object> out = Diff.compare(base, d, "v1", "current", null, null, true, Standards.defaults(), null);
		assertTrue(out.get("summary").toString().contains("No differences"), out.get("summary").toString());
		for (String g : List.of("vehicle", "flight")) {
			for (Map<String, Object> r : list(out, g)) {
				assertEquals("", r.get("change"), r.toString());
			}
		}
		assertEquals("no component changes", list(out, "componentChanges").get(0).get("result"));
		assertEquals("no change in any rule check", list(out, "ruleCheckChanges").get(0).get("result"));
	}

	@Test
	void editsShowUpAsComponentAndPerformanceChanges() throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("Dual parachute");
		Path v1 = designs.save(d, tmp.resolve("v1.ork"));
		var fins = StudiesTest.first(d, info.openrocket.core.rocketcomponent.TrapezoidFinSet.class);
		fins.setHeight(fins.getHeight() * 1.3);
		MassComponent ballast = new MassComponent();
		ballast.setName("Nose ballast");
		ballast.setComponentMass(0.2);
		StudiesTest.first(d, BodyTube.class).addChild(ballast);
		Analysis.settle(d.doc.getRocket().getSelectedConfiguration());

		Path md = tmp.resolve("review/changes.md");
		Map<String, Object> out = Diff.compare(Diff.load(v1, "file"), d, "v1", "v2", null, null, true, Standards.defaults(), md);
		List<Map<String, Object>> comps = list(out, "componentChanges");
		Map<String, Object> finEdit = comps.stream().filter(c -> c.get("component").toString().startsWith(fins.getName()))
				.findFirst().orElseThrow();
		assertEquals("edited", finEdit.get("change"));
		assertTrue(finEdit.get("edits").toString().contains("height"), finEdit.toString());
		Map<String, Object> added = comps.stream().filter(c -> c.get("component").toString().startsWith("Nose ballast")
				&& c.get("change").toString().startsWith("added")).findFirst().orElseThrow();
		// This example's stage overrides the mass of its subcomponents: the diff says the ballast does not count.
		assertTrue(String.valueOf(added.get("warning")).contains("overrides the mass"), added.toString());

		// Bigger fins (their mass is hidden by the override too): more drag and more stable.
		assertTrue(row(out, "flight", "apogee").get("change").toString().startsWith("-"), row(out, "flight", "apogee").toString());
		assertTrue(row(out, "vehicle", "static stability at launch").get("change").toString().startsWith("+"));
		assertEquals("", row(out, "vehicle", "motors").get("change"));
		String text = Files.readString(md);
		assertTrue(text.contains("# Design changes: v1 -> v2") && text.contains("Nose ballast") && text.contains("| apogee |"), text);
	}

	@Test
	void comparesWithAnEarlierGitRevision() throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("A simple model rocket");
		Path file = designs.save(d, tmp.resolve("rocket.ork"));
		git("init", "-q");
		git("add", "rocket.ork");
		git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "v1");
		FinSet fins = StudiesTest.first(d, FinSet.class);
		fins.setFinCount(fins.getFinCount() + 1);
		designs.save(d, file);

		Designs.Design base = Diff.loadRevision(d, "HEAD");
		Map<String, Object> out = Diff.compare(base, d, "HEAD", "working copy", null, null, true, Standards.defaults(), null);
		assertTrue(list(out, "componentChanges").stream().anyMatch(c -> String.valueOf(c.get("edits")).contains("finCount")),
				out.get("componentChanges").toString());
		try {
			Diff.loadRevision(d, "--output=/tmp/x");
			throw new AssertionError("option injection accepted");
		} catch (io.github.openrocketmcp.mcp.ToolException expected) {
			// refused
		}
	}

	private void git(String... args) throws Exception {
		String[] cmd = new String[args.length + 3];
		cmd[0] = "git";
		cmd[1] = "-C";
		cmd[2] = tmp.toString();
		System.arraycopy(args, 0, cmd, 3, args.length);
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String o = new String(p.getInputStream().readAllBytes());
		assertEquals(0, p.waitFor(), o);
	}
}
