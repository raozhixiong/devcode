package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {"lobster.state-dir=target/test-state-t3"})
class DatabaseConfigTest {
    @Autowired DataSource ds;

    @Test
    void migrationsApplied() {
        var jdbc = new JdbcTemplate(ds);
        List<String> tables = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", String.class);
        for (String t : new String[]{"user", "agent", "task", "workboard_card",
                "approval", "cron_job", "secret_store_entry", "audit_event"}) {
            assertTrue(tables.contains(t), "missing table " + t);
        }
        String mode = jdbc.queryForObject("PRAGMA journal_mode", String.class);
        assertEquals("wal", mode.toLowerCase());
    }
}
