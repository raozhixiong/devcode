package com.lobster.tool;

import java.util.List;

/** 权限请求（工具 -> 引擎 -> 用户）。 */
public record PermissionRequest(String permission, List<String> patterns) {}
