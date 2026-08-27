package com.lobster.store;

import com.lobster.event.EventBus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 用户管理：CRUD + 密码哈希。 */
class UserStoreTest {

    @TempDir Path tmp;

    private UserStore store(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb agentDb = AgentDb.open(dir.resolve("agents"), "test")) {
            return new UserStore(new JdbcTemplate(ds), new EventBus(agentDb));
        }
    }

    @Test
    void createAndFindByUsername() {
        var us = store(tmp);
        var user = us.create("alice", "Alice", "alice@test.com", "pass123", "admin");
        assertEquals("usr_", user.id().substring(0, 4));
        assertEquals("alice", user.username());
        assertEquals("admin", user.role());
        assertEquals("active", user.status());

        var found = us.findByUsername("alice");
        assertTrue(found.isPresent());
        assertEquals(user.id(), found.get().id());
        assertEquals("Alice", found.get().displayName());
    }

    @Test
    void verifyPasswordCorrectAndWrong() {
        var us = store(tmp);
        us.create("bob", "Bob", null, "secret456", "developer");

        var hash = us.findPasswordHash("bob").orElseThrow();
        assertTrue(UserStore.verifyPassword("secret456", hash));
        assertFalse(UserStore.verifyPassword("wrong", hash));
        assertFalse(UserStore.verifyPassword("", hash));
    }

    @Test
    void countAndList() {
        var us = store(tmp);
        assertEquals(0, us.count());
        us.create("u1", "User 1", null, "p1", "developer");
        us.create("u2", "User 2", null, "p2", "reviewer");
        assertEquals(2, us.count());

        var list = us.list();
        assertEquals(2, list.size());
        assertEquals("u1", list.get(0).username());
    }

    @Test
    void updatePassword() {
        var us = store(tmp);
        var user = us.create("carol", "Carol", null, "oldpass", "pm");
        us.updatePassword(user.id(), "newpass");

        var hash = us.findPasswordHash("carol").orElseThrow();
        assertFalse(UserStore.verifyPassword("oldpass", hash));
        assertTrue(UserStore.verifyPassword("newpass", hash));
    }

    @Test
    void setStatusDisabled() {
        var us = store(tmp);
        var user = us.create("dave", "Dave", null, "p", "ops");
        us.setStatus(user.id(), "disabled");
        assertEquals("disabled", us.findById(user.id()).orElseThrow().status());
    }

    @Test
    void delete() {
        var us = store(tmp);
        var user = us.create("eve", "Eve", null, "p", "admin");
        us.delete(user.id());
        assertTrue(us.findByUsername("eve").isEmpty());
        assertEquals(0, us.count());
    }

    @Test
    void duplicateUsernameThrows() {
        var us = store(tmp);
        us.create("frank", "Frank", null, "p", "admin");
        assertThrows(Exception.class, () ->
                us.create("frank", "Frank2", null, "p2", "developer"));
    }
}
