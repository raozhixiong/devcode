package com.lobster.auth;

/** 已认证会话信息（WsHandler 在 connect 成功后缓存）。 */
public record AuthInfo(
        String userId,
        String username,
        String role,
        String scopes,
        String tokenId) {

    public boolean hasScope(String scope) {
        if (scopes == null || scopes.isEmpty()) return false;
        if (scopes.equals("*")) return true;
        for (String s : scopes.split(",")) {
            if (s.trim().equals(scope) || s.trim().equals("*")) return true;
        }
        return false;
    }
}
