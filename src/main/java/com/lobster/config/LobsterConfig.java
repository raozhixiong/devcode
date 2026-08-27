package com.lobster.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** MCP server 定义（FR-A4-7）：stdio 传输启动子进程。 */
    public record McpServer(String name, String transport, String command,
                            List<String> args, Map<String, String> env) {}

    private final LlmSettings llm;
    private final List<RuleItem> rules;
    private final List<McpServer> mcpServers;

    public LobsterConfig(Path stateDir, String configOverride) {
        Path p = (configOverride == null || configOverride.isBlank())
                ? stateDir.resolve("lobster.json")
                : Path.of(configOverride);
        LlmSettings parsedLlm = null;
        List<RuleItem> parsedRules = new ArrayList<>();
        List<McpServer> parsedMcp = new ArrayList<>();
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
                JsonNode m = root.path("mcp");
                if (m.isObject()) {
                    JsonNode servers = m.path("servers");
                    if (servers.isArray()) {
                        for (JsonNode s : servers) {
                            List<String> args = new ArrayList<>();
                            if (s.path("args").isArray()) {
                                for (JsonNode a : s.path("args")) args.add(a.asText());
                            }
                            Map<String, String> env = new LinkedHashMap<>();
                            JsonNode e = s.path("env");
                            if (e.isObject()) {
                                e.fields().forEachRemaining(x -> env.put(x.getKey(), x.getValue().asText()));
                            }
                            parsedMcp.add(new McpServer(
                                    s.path("name").asText(),
                                    s.path("transport").asText("stdio"),
                                    s.path("command").asText(),
                                    args, env));
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("lobster.json 解析失败: " + p, e);
            }
        }
        this.llm = parsedLlm;
        this.rules = List.copyOf(parsedRules);
        this.mcpServers = List.copyOf(parsedMcp);
    }

    public LlmSettings llm() { return llm; }

    public boolean hasRealLlm() { return llm != null; }

    public List<RuleItem> permissionRules() { return rules; }

    public List<McpServer> mcpServers() { return mcpServers; }
}
