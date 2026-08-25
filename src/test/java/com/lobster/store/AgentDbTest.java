package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentDbTest {
    @Test
    void opensAndMigrates(@TempDir Path tmp) {
        try (AgentDb db = AgentDb.open(tmp, "dev-01")) {
            List<String> tables = db.jdbc().queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", String.class);
            for (String t : new String[]{"session", "message", "part",
                    "session_input", "session_active_writer", "event", "event_sequence", "todo"}) {
                assertTrue(tables.contains(t), "missing " + t);
            }
        }
    }
}
