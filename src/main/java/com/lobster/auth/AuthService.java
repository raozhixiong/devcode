package com.lobster.auth;

import com.lobster.store.AuthTokenStore;
import com.lobster.store.DeviceStore;
import com.lobster.store.UserStore;

import java.util.Optional;

/** 认证编排（对齐 FR-G-1/G-2）：bootstrap / login / validateToken。 */
public class AuthService {

    private final UserStore users;
    private final AuthTokenStore tokens;
    private final DeviceStore devices;

    public AuthService(UserStore users, AuthTokenStore tokens, DeviceStore devices) {
        this.users = users;
        this.tokens = tokens;
        this.devices = devices;
    }

    public record BootstrapResult(String token, UserStore.User user) {}
    public record LoginResult(String token, UserStore.User user) {}

    public boolean isAuthRequired() {
        return users.count() > 0;
    }

    public int userCount() {
        return users.count();
    }

    public Optional<AuthInfo> validateToken(String token) {
        if (token == null || token.isEmpty()) return Optional.empty();
        var info = tokens.validate(token);
        if (info.isEmpty()) return Optional.empty();
        var user = users.findById(info.get().userId());
        if (user.isEmpty()) return Optional.empty();
        if (!"active".equals(user.get().status())) return Optional.empty();
        return Optional.of(new AuthInfo(
                user.get().id(), user.get().username(), user.get().role(),
                info.get().scopes(), info.get().id()));
    }

    public BootstrapResult bootstrap(String username, String displayName,
                                     String password, String role) {
        if (users.count() > 0) {
            throw new IllegalStateException("系统已初始化，不能 bootstrap");
        }
        var user = users.create(username, displayName, null, password, role);
        var token = tokens.create("bootstrap-" + username, user.id(), "*", null);
        return new BootstrapResult(token.token(), user);
    }

    public LoginResult login(String username, String password) {
        var user = users.findByUsername(username);
        if (user.isEmpty()) throw new IllegalArgumentException("用户不存在");
        var hash = users.findPasswordHash(username);
        if (hash.isEmpty() || !UserStore.verifyPassword(password, hash.get())) {
            throw new IllegalArgumentException("密码错误");
        }
        if (!"active".equals(user.get().status())) {
            throw new IllegalStateException("用户已禁用");
        }
        var token = tokens.create("login-" + username, user.get().id(), "*", null);
        return new LoginResult(token.token(), user.get());
    }

    public UserStore.User createUser(String username, String displayName,
                                     String email, String password, String role) {
        return users.create(username, displayName, email, password, role);
    }

    public AuthTokenStore.CreateResult createToken(String name, String userId,
                                                    String scopes, Long expiresAt) {
        return tokens.create(name, userId, scopes, expiresAt);
    }

    public void revokeToken(String tokenId) {
        tokens.revoke(tokenId);
    }

    public UserStore users() { return users; }
    public AuthTokenStore tokens() { return tokens; }
    public DeviceStore devices() { return devices; }
}
