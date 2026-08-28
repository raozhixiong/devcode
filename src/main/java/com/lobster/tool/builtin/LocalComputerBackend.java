package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** 本地计算机后端：截图走可配置命令，其余动作记录意图（FR-I6）。 */
public class LocalComputerBackend implements ComputerBackend {

    private final Function<String, String> cfg;

    public LocalComputerBackend(Function<String, String> cfg) {
        this.cfg = cfg;
    }

    @Override
    public Map<String, Object> act(String action, JsonNode params) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", action);
        switch (action) {
            case "screenshot" -> {
                String cmd = cfg.apply("computer.screenshot_cmd");
                if (cmd == null || cmd.isBlank()) {
                    r.put("status", "error");
                    r.put("message", "未配置 computer.screenshot_cmd");
                    return r;
                }
                Process p = new ProcessBuilder(cmd.split("\\s+")).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                int code = p.waitFor();
                if (code != 0) {
                    r.put("status", "error");
                    r.put("message", out);
                    return r;
                }
                byte[] bytes = Files.readAllBytes(java.nio.file.Path.of(cfg.apply("computer.screenshot_path")));
                r.put("status", "ok");
                r.put("data", Base64.getEncoder().encodeToString(bytes));
            }
            case "navigate", "click", "type", "scroll" -> {
                r.put("status", "ok");
                r.put("message", "已在本地后端记录动作（需真实浏览器驱动执行）");
            }
            default -> {
                r.put("status", "error");
                r.put("message", "未知动作: " + action);
            }
        }
        return r;
    }
}
