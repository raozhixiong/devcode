package com.lobster.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * lobster.json 运行时配置（~/.lobster/lobster.json 或 lobster.config 路径覆盖）。
 * 无配置文件或缺少 llm 段时回退 Mock provider。
 */
public class LobsterConfig {

    private static final ObjectMapper OM = new ObjectMapper();

    public record LlmSettings(String provider, String baseUrl, String apiKey,
                              String model, double temperature, long contextLimit) {}

    /** 权限规则三元组：permission, pattern, action。 */
    public record RuleItem(String permission, String pattern, String action) {}

    private final LlmSettings llm;
    private final List<RuleItem> rules;

    public LobsterConfig(Path stateDir, String configOverride) {
        Path p = (configOverride == null || configOverride.isBlank())
                ? stateDir.resolve("lobster.json")
                : Path.of(configOverride);
        LlmSettings parsedLlm = null;
        List<RuleItem> parsedRules = new ArrayList<>();
        if (Files.isRegularFile(p)) {
            try {
                JsonNode root = OM.readTree(Files.readString(p));
                JsonNode n = root.path("llm");
                if (!n.isMissingNode() && n.hasNonNull("apiKey") && n.hasNonNull("baseUrl")) {
                    parsedLlm = new LlmSettings(
                            n.path("provider").asText("openai-compat"),
                            n.path("baseUrl").asText(),
                            n.path("apiKey").asText(),
                            n.path("model").asText("gpt-4o-mini"),
                            n.path("temperature").asDouble(0.7),
                            n.path("contextLimit").asLong(128_000));
                }
                JsonNode r = root.path("permissions");
                if (r.isArray()) {
                    for (JsonNode item : r) {
                        parsedRules.add(new RuleItem(
                                item.path("permission").asText(),
                                item.path("pattern").asText("*"),
                                item.path("action").asText("ASK")));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("lobster.json 解析失败: " + p, e);
            }
        }
        this.llm = parsedLlm;
        this.rules = List.copyOf(parsedRules);
    }

    public LlmSettings llm() { return llm; }

    public boolean hasRealLlm() { return llm != null; }

    public List<RuleItem> permissionRules() { return rules; }
}
