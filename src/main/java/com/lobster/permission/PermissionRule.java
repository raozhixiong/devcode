package com.lobster.permission;

/** 规则：permission + pattern + action。eval 时 findLast 匹配优先。 */
public record PermissionRule(String permission, String pattern, Action action) {

    public enum Action { ALLOW, DENY, ASK }

    /** 匹配：pattern 含 / 时按路径段匹配（段内 * 不跨 /）；不含 / 时整串模糊匹配（* 跨任意字符）。 */
    public boolean matches(String permission, String target) {
        if (!this.permission.equals(permission) && !"*".equals(this.permission)) return false;
        return globMatch(pattern, target);
    }

    static boolean globMatch(String pattern, String target) {
        if (pattern.indexOf('/') < 0) {
            return java.util.regex.Pattern.compile(regexFrom(pattern, true))
                    .matcher(target).matches();
        }
        String[] pParts = pattern.split("/", -1);
        String[] tParts = target.split("/", -1);
        return matchParts(pParts, 0, tParts, 0);
    }

    private static boolean matchParts(String[] p, int pi, String[] t, int ti) {
        while (pi < p.length) {
            if ("**".equals(p[pi])) {
                if (pi == p.length - 1) return true;
                for (int k = ti; k <= t.length; k++) {
                    if (matchParts(p, pi + 1, t, k)) return true;
                }
                return false;
            }
            if (ti >= t.length) return false;
            if (!java.util.regex.Pattern.compile(regexFrom(p[pi], false))
                    .matcher(t[ti]).matches()) return false;
            pi++;
            ti++;
        }
        return ti == t.length;
    }

    /** any=true 时 * 跨任意字符；false 时 * 限于段内（不跨 /）。 */
    private static String regexFrom(String seg, boolean any) {
        StringBuilder sb = new StringBuilder();
        for (char c : seg.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(any ? ".*" : "[^/]*");
                case '?' -> sb.append(any ? "." : "[^/]");
                case '.', '(', ')', '[', ']', '{', '}', '\\', '^', '$', '|' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
