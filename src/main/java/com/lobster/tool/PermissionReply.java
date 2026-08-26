package com.lobster.tool;

/** 权限回复（引擎/用户 -> 工具）。 */
public record PermissionReply(Decision decision, String feedback) {

    public enum Decision { ALLOW_ONCE, ALLOW_ALWAYS, REJECT }

    public boolean allowed() { return decision != Decision.REJECT; }
}
