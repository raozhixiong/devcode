package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** 语音/TTS 工具（FR-I7 脚手架）：调用 OpenAI 兼容 /audio/speech 端点。未配置时返回提示。 */
public class TtsTool implements Tool {

    private final Path stateDir;
    private final Function<String, String> cfg;
    private static final ObjectMapper OM = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public TtsTool(Path stateDir, Function<String, String> cfg) {
        this.stateDir = stateDir;
        this.cfg = cfg;
    }

    @Override
    public String id() { return "tts"; }

    @Override
    public String description() {
        return "将文本合成为语音（TTS）。需要 lobster.tts.baseUrl 与 lobster.tts.apiKey（OpenAI 兼容 /audio/speech）。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", Map.of("type", "string", "description", "要合成的文本"));
        props.put("voice", Map.of("type", "string", "description", "音色，默认从配置读取"));
        p.put("properties", props);
        p.put("required", java.util.List.of("text"));
        return p;
    }

    @Override
    public ToolResult execute(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) throws Exception {
        String text = args.path("text").asText();
        if (text.isBlank()) {
            return ToolResult.of("tts", "{\"ok\":false,\"error\":\"text 必填\"}");
        }
        String baseUrl = cfg.apply("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            return ToolResult.of("tts", "{\"ok\":false,\"error\":\"TTS 未配置：设置 lobster.tts.baseUrl / lobster.tts.apiKey\"}");
        }
        String apiKey = cfg.apply("apiKey");
        String model = cfg.apply("model");
        if (model == null || model.isBlank()) model = "tts-1";
        String voice = args.path("voice").asText();
        if (voice.isBlank()) voice = cfg.apply("voice");
        if (voice == null || voice.isBlank()) voice = "alloy";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);
        body.put("voice", voice);
        body.put("response_format", "mp3");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/$", "") + "/audio/speech"))
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30)).build();
        HttpResponse<java.nio.file.Path> r = http.send(req, HttpResponse.BodyHandlers.ofFile(
                Files.createDirectories(stateDir.resolve("tts"))
                        .resolve("tts-" + java.util.UUID.randomUUID() + ".mp3")));
        if (r.statusCode() >= 400) {
            return ToolResult.of("tts", "{\"ok\":false,\"error\":\"TTS 请求失败 HTTP " + r.statusCode() + "\"}");
        }
        String url = r.body().toString();
        return ToolResult.of("tts", "{\"ok\":true,\"url\":\"" + url.replace("\\", "/") + "\",\"voice\":\"" + voice + "\"}");
    }
}
