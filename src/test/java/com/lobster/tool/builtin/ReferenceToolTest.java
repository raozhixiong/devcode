package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.event.EventBus;
import com.lobster.store.AgentDb;
import com.lobster.store.ReferenceLoader;
import com.lobster.store.ReferenceStore;
import com.lobster.tool.ToolContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceToolTest {

    @Test
    void readsReferenceContent(@TempDir Path dir) throws Exception {
        Path doc = dir.resolve("ref.md");
        Files.writeString(doc, "reference body content");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb adb = AgentDb.open(dir.resolve("agents"), "test")) {
            var rs = new ReferenceStore(new JdbcTemplate(ds), new EventBus(adb));
            rs.install("stdlib", "local", doc.toString(), "std library");
            var loader = new ReferenceLoader(p -> null);
            var tool = new ReferenceTool(rs, loader, dir);
            JsonNode args = new ObjectMapper().readTree("{\"name\":\"stdlib\"}");
            var res = tool.execute(args, ToolContext.dummy());
            assertTrue(res.output().contains("reference body content"));
        }
    }
}
