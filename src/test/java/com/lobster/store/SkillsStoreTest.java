package com.lobster.store;

import com.lobster.event.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Skills 加载器：扫描/安装/启停技能。 */
class SkillsStoreTest {

    @TempDir Path tmp;

    private SkillsStore store(Path dir) {
        try (AgentDb db = AgentDb.open(dir.resolve("agents"), "skills")) {
            return new SkillsStore(dir, new EventBus(db));
        }
    }

    @Test
    void installAndGet() {
        var ss = store(tmp);
        var skill = ss.install("code-review", "# Code Review\n检查代码质量、风格、安全。");
        assertEquals("code-review", skill.name());
        assertTrue(skill.content().contains("检查代码质量"));
        assertTrue(skill.enabled());

        var found = ss.get("code-review");
        assertTrue(found.isPresent());
        assertEquals("Code Review", found.get().description());
    }

    @Test
    void listMultiple() {
        var ss = store(tmp);
        ss.install("skill-a", "# Skill A\n描述 A");
        ss.install("skill-b", "# Skill B\n描述 B");

        List<SkillsStore.Skill> skills = ss.list();
        assertEquals(2, skills.size());
    }

    @Test
    void enableDisable() {
        var ss = store(tmp);
        ss.install("test-skill", "# Test\n描述");
        assertTrue(ss.get("test-skill").orElseThrow().enabled());

        ss.setEnabled("test-skill", false);
        assertFalse(ss.get("test-skill").orElseThrow().enabled());

        ss.setEnabled("test-skill", true);
        assertTrue(ss.get("test-skill").orElseThrow().enabled());
    }

    @Test
    void enabledNames() {
        var ss = store(tmp);
        ss.install("active", "# Active\nactive skill");
        ss.install("inactive", "# Inactive\ninactive skill");
        ss.setEnabled("inactive", false);

        List<String> names = ss.enabledNames();
        assertEquals(1, names.size());
        assertEquals("active", names.get(0));
    }

    @Test
    void getNonExistent() {
        var ss = store(tmp);
        assertTrue(ss.get("nope").isEmpty());
    }
}
