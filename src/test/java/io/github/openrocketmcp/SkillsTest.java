package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Each skill ships twice: for Claude (.claude/skills) and for Codex and other agents (.agents/skills). */
class SkillsTest {
	@Test
	void claudeAndCodexSkillsMatch() throws Exception {
		for (String skill : new String[] { "rocket-design-review", "team-chief-engineer" }) {
			String claude = Files.readString(Path.of(".claude/skills/" + skill + "/SKILL.md"));
			String agents = Files.readString(Path.of(".agents/skills/" + skill + "/SKILL.md"));
			assertEquals(claude.replace("\r\n", "\n"), agents.replace("\r\n", "\n"),
					"Edit .claude/skills/" + skill + "/SKILL.md and copy it to .agents/skills/" + skill + "/");
		}
	}
}
