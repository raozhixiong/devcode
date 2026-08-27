package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 用户管理（对齐 FR-G-1）：user 表 CRUD + 密码哈希。 */
public class UserStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public UserStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record User(String id, String username, String displayName, String email,
                       String avatarUrl, String role, String status,
                       long createdAt, long updatedAt) {}

    public User create(String username, String displayName, String email,
                       String password, String role) {
        String id = Ulid.next("usr_");
        long now = System.currentTimeMillis();
        String passwordHash = hashPassword(password, generateSalt());
        jdbc.update("""
                INSERT INTO user(id, username, display_name, email, role, status, password_hash, created_at, updated_at)
                VALUES(?,?,?,?,?,'active',?,?,?)
                """, id, username, displayName, email, role, passwordHash, now, now);
        publishChanged(id, "created");
        return new User(id, username, displayName, email, null, role, "active", now, now);
    }

    public Optional<User> findById(String id) {
        return jdbc.query("""
                SELECT id, username, display_name, email, avatar_url, role, status, created_at, updated_at
                FROM user WHERE id = ?
                """, UserStore::mapUser, id).stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.query("""
                SELECT id, username, display_name, email, avatar_url, role, status, created_at, updated_at
                FROM user WHERE username = ?
                """, UserStore::mapUser, username).stream().findFirst();
    }

    public Optional<String> findPasswordHash(String username) {
        return jdbc.query("SELECT password_hash FROM user WHERE username = ?",
                (rs, i) -> rs.getString("password_hash"), username)
                .stream().findFirst();
    }

    public List<User> list() {
        return jdbc.query("""
                SELECT id, username, display_name, email, avatar_url, role, status, created_at, updated_at
                FROM user ORDER BY created_at
                """, UserStore::mapUser);
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class);
        return n == null ? 0 : n;
    }

    public void updateProfile(String id, String displayName, String email, String avatarUrl) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE user SET display_name=?, email=?, avatar_url=?, updated_at=? WHERE id=?",
                displayName, email, avatarUrl, now, id);
        publishChanged(id, "updated");
    }

    public void updatePassword(String id, String newPassword) {
        String hash = hashPassword(newPassword, generateSalt());
        jdbc.update("UPDATE user SET password_hash=?, updated_at=? WHERE id=?",
                hash, System.currentTimeMillis(), id);
    }

    public void setStatus(String id, String status) {
        jdbc.update("UPDATE user SET status=?, updated_at=? WHERE id=?",
                status, System.currentTimeMillis(), id);
        publishChanged(id, status.equals("disabled") ? "disabled" : "updated");
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM user WHERE id=?", id);
        publishChanged(id, "deleted");
    }

    public static boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) return false;
        int colon = storedHash.indexOf(':');
        if (colon < 0) return false;
        String salt = storedHash.substring(0, colon);
        String expected = storedHash.substring(colon + 1);
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).equals(expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static String hashPassword(String password, String salt) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return salt + ":" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    private static User mapUser(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new User(
                rs.getString("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("email"),
                rs.getString("avatar_url"), rs.getString("role"),
                rs.getString("status"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private void publishChanged(String userId, String action) {
        ObjectNode data = OM.createObjectNode().put("userId", userId).put("action", action);
        bus.publish(new LobsterEvent(Events.AUTH_USER_CHANGED, "", data, false));
    }
}
