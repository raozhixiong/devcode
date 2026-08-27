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

/** Auth token 管理（对齐 FR-G-1）：auth_token 表 CRUD + SHA-256 哈希。 */
public class AuthTokenStore {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EventBus bus;

    public AuthTokenStore(JdbcTemplate sharedJdbc, EventBus bus) {
        this.jdbc = sharedJdbc;
        this.bus = bus;
    }

    public record TokenInfo(String id, String name, String userId, String scopes,
                            long createdAt, Long expiresAt, Long revokedAt) {}

    public record CreateResult(String tokenId, String token, TokenInfo info) {}

    public CreateResult create(String name, String userId, String scopes, Long expiresAt) {
        String id = Ulid.next("tok_");
        long now = System.currentTimeMillis();
        String token = generateToken();
        String hash = hashToken(token);
        jdbc.update("""
                INSERT INTO auth_token(id, name, token_hash, scopes, created_by, created_at, expires_at)
                VALUES(?,?,?,?,?,?,?)
                """, id, name, hash, scopes, userId, now, expiresAt);
        return new CreateResult(id, token, new TokenInfo(id, name, userId, scopes, now, expiresAt, null));
    }

    public Optional<TokenInfo> validate(String token) {
        if (token == null || token.isEmpty()) return Optional.empty();
        String hash = hashToken(token);
        List<TokenInfo> results = jdbc.query("""
                SELECT id, name, created_by, scopes, created_at, expires_at, revoked_at
                FROM auth_token WHERE token_hash = ? AND revoked_at IS NULL
                """, (rs, i) -> new TokenInfo(
                rs.getString("id"), rs.getString("name"),
                rs.getString("created_by"), rs.getString("scopes"),
                rs.getLong("created_at"),
                nLong(rs, "expires_at"),
                nLong(rs, "revoked_at")), hash);
        if (results.isEmpty()) return Optional.empty();
        var info = results.get(0);
        if (info.expiresAt() != null && System.currentTimeMillis() > info.expiresAt()) {
            return Optional.empty();
        }
        return Optional.of(info);
    }

    public List<TokenInfo> listByUser(String userId) {
        return jdbc.query("""
                SELECT id, name, created_by, scopes, created_at, expires_at, revoked_at
                FROM auth_token WHERE created_by = ? ORDER BY created_at DESC
                """, (rs, i) -> new TokenInfo(
                rs.getString("id"), rs.getString("name"),
                rs.getString("created_by"), rs.getString("scopes"),
                rs.getLong("created_at"),
                nLong(rs, "expires_at"),
                nLong(rs, "revoked_at")), userId);
    }

    public List<TokenInfo> list() {
        return jdbc.query("""
                SELECT id, name, created_by, scopes, created_at, expires_at, revoked_at
                FROM auth_token ORDER BY created_at DESC
                """, (rs, i) -> new TokenInfo(
                rs.getString("id"), rs.getString("name"),
                rs.getString("created_by"), rs.getString("scopes"),
                rs.getLong("created_at"),
                nLong(rs, "expires_at"),
                nLong(rs, "revoked_at")));
    }

    private static Long nLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : ((Number) o).longValue();
    }

    public void revoke(String tokenId) {
        jdbc.update("UPDATE auth_token SET revoked_at=? WHERE id=? AND revoked_at IS NULL",
                System.currentTimeMillis(), tokenId);
        ObjectNode data = OM.createObjectNode().put("tokenId", tokenId);
        bus.publish(new LobsterEvent(Events.AUTH_TOKEN_REVOKED, "", data, false));
    }

    public void revokeAllForUser(String userId) {
        jdbc.update("UPDATE auth_token SET revoked_at=? WHERE created_by=? AND revoked_at IS NULL",
                System.currentTimeMillis(), userId);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "lst_" + HexFormat.of().formatHex(bytes);
    }

    private static String hashToken(String token) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
