package com.lobster.rbac;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 角色体系：Role 工具 allowlist + AgentRegistry CRUD + 角色过滤。 */
class RbacTest {

    @TempDir Path tmp;

    private AgentRegistry registry(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        return new AgentRegistry(new JdbcTemplate(ds), dir);
    }

    @Test
    void roleToolAllowlists() {
        assertTrue(Role.DEVELOPER.toolAllowed("bash"));
        assertTrue(Role.DEVELOPER.toolAllowed("edit"));
        assertFalse(Role.REVIEWER.toolAllowed("bash"));
        assertFalse(Role.REVIEWER.toolAllowed("write"));
        assertTrue(Role.REVIEWER.toolAllowed("read"));
        assertTrue(Role.TESTER.toolAllowed("bash"));
        assertFalse(Role.TESTER.toolAllowed("edit"));
        assertFalse(Role.PM.toolAllowed("bash"));
        assertTrue(Role.KNOWLEDGE.toolAllowed("write"));
        assertThrows(IllegalArgumentException.class, () -> Role.of("nobody"));
    }

    @Test
    void agentCrudAndFilter() {
        var reg = registry(tmp);
        var dev = reg.create("developer-01", "developer", "💻", "openai-compat", "gpt-4o-mini");
        var rev = reg.create("reviewer-01", "reviewer", "🔍", null, null);

        assertEquals("developer", dev.role());
        assertTrue(dev.id().startsWith("agt_"));
        assertTrue(dev.permissionRules().contains("bash"));

        // 工具过滤器按角色生效
        var devFilter = reg.toolFilter(dev.id());
        var revFilter = reg.toolFilter(rev.id());
        assertTrue(devFilter.test("bash"));
        assertFalse(revFilter.test("bash"));
        assertTrue(revFilter.test("read"));

        // 更新与删除
        reg.updateModel(rev.id(), "openai-compat", "deepseek-chat");
        assertEquals("deepseek-chat", reg.get(rev.id()).orElseThrow().modelId());
        assertEquals(2, reg.list().size());
        reg.delete(rev.id());
        assertEquals(1, reg.list().size());
        assertTrue(reg.get(rev.id()).isEmpty());
    }
}
