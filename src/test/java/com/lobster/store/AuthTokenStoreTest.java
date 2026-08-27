package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Auth token：创建 + 验证 + 吊销 + 过期。 */
class AuthTokenStoreTest {

    @TempDir Path tmp;

    private AuthTokenStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "test")) {
            return new AuthTokenStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void createAndValidate() {
        var ts = store(tmp);
        var result = ts.create("test-token", "usr_1", "*", null);
        assertEquals("tok_", result.tokenId().substring(0, 4));
        assertTrue(result.token().startsWith("lst_"));

        var info = ts.validate(result.token());
        assertTrue(info.isPresent());
        assertEquals(result.tokenId(), info.get().id());
        assertEquals("usr_1", info.get().userId());
        assertEquals("*", info.get().scopes());
        assertNull(info.get().revokedAt());
    }

    @Test
    void validateInvalidToken() {
        var ts = store(tmp);
        assertTrue(ts.validate("lst_invalid").isEmpty());
        assertTrue(ts.validate("").isEmpty());
        assertTrue(ts.validate(null).isEmpty());
    }

    @Test
    void revoke() {
        var ts = store(tmp);
        var result = ts.create("revoke-test", "usr_1", "*", null);

        assertTrue(ts.validate(result.token()).isPresent());
        ts.revoke(result.tokenId());
        assertTrue(ts.validate(result.token()).isEmpty());
    }

    @Test
    void expiredToken() {
        var ts = store(tmp);
        long pastExpiry = System.currentTimeMillis() - 10000;
        var result = ts.create("expired", "usr_1", "read", pastExpiry);

        assertTrue(ts.validate(result.token()).isEmpty());
    }

    @Test
    void listByUser() {
        var ts = store(tmp);
        ts.create("t1", "usr_a", "*", null);
        ts.create("t2", "usr_a", "read", null);
        ts.create("t3", "usr_b", "*", null);

        var tokens = ts.listByUser("usr_a");
        assertEquals(2, tokens.size());
        assertTrue(tokens.stream().allMatch(t -> "usr_a".equals(t.userId())));
    }

    @Test
    void revokeAllForUser() {
        var ts = store(tmp);
        var r1 = ts.create("t1", "usr_x", "*", null);
        var r2 = ts.create("t2", "usr_x", "read", null);
        ts.create("t3", "usr_y", "*", null);

        ts.revokeAllForUser("usr_x");
        assertTrue(ts.validate(r1.token()).isEmpty());
        assertTrue(ts.validate(r2.token()).isEmpty());
    }
}
