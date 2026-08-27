package com.lobster.tool;

import java.util.List;

/** 权限请求（工具 -> 引擎 -> 用户）。sessionId 用于事件路由（可空）。 */
public record PermissionRequest(String permission, List<String> patterns, String sessionId) {

    public PermissionRequest(String permission, List<String> patterns) {
        this(permission, patterns, null);
    }
}
