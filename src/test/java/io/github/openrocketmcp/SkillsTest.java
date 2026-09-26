package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The design-review skill ships twice: for Claude (.claude/skills) and for Codex and other agents (.agents/skills). */
class SkillsTest {
	@Test
	void claudeAndCodexSkillsMatch() throws Exception {
		String claude = Files.readString(Path.of(".claude/skills/rocket-design-review/SKILL.md"));
		String agents = Files.readString(Path.of(".agents/skills/rocket-design-review/SKILL.md"));
		assertEquals(claude.replace("\r\n", "\n"), agents.replace("\r\n", "\n"),
				"Edit .claude/skills/rocket-design-review/SKILL.md and copy it to .agents/skills/rocket-design-review/");
	}
}
