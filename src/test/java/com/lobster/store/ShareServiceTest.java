package com.lobster.store;

import com.lobster.event.EventBus;
import com.lobster.model.Part;
import com.lobster.util.Ulid;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShareServiceTest {

    @TempDir Path tmp;

    @Test
    void createAndExport() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (var agentDb = com.lobster.store.AgentDb.open(tmp.resolve("agents"), "test")) {
            var ms = new MessageStore(agentDb);
            var svc = new ShareService(new JdbcTemplate(ds), ms, new EventBus(agentDb));
            var sess = ms.createSession("sk_" + Ulid.next("sk_"), "conversation", System.getProperty("user.dir"));
            ms.appendUser(sess.id(), List.of(new Part.Text("hi there", false, false)));
            String token = svc.create(sess.id());
            assertNotNull(token);
            var arr = svc.exportMessages(token);
            assertEquals(1, arr.size());
            assertEquals("hi there", arr.get(0).get("parts").get(0).path("text").asText());
        }
    }
}
