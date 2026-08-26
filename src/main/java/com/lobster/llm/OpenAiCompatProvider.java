package com.lobster.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * OpenAI 兼容流式 Provider（/chat/completions, stream:true）。
 * 适配：OpenAI / DeepSeek / 智谱 / 火山方舟 / Kimi / Ollama 等兼容端点。
 */
public class OpenAiCompatProvider implements LlmProvider {

    private static final ObjectMapper OM = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final String baseUrl;
    private final String apiKey;

    public OpenAiCompatProvider(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public Stream<LlmEvent> stream(LlmRequest req) {
        // SSE 读取为阻塞式；AgentLoop 在虚拟线程中消费
        return streamBlocking(req);
    }
    public Stream<LlmEvent> streamBlocking(LlmRequest req) {
        try {
            HttpRequest httpReq = buildRequest(req);
            HttpResponse<java.io.InputStream> resp =
                    http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String err = new String(resp.body().readAllBytes());
                return Stream.of(new LlmEvent.Error(
                        new IllegalStateException("LLM HTTP " + resp.statusCode() + ": " + err)));
            }
            List<LlmEvent> events = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                String finishReason = "stop";
                LlmEvent.Usage usage = new LlmEvent.Usage(0, 0);
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) break;
                    JsonNode root = OM.readTree(payload);
                    JsonNode choice = root.path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText(null);
                    if (delta != null && !delta.isEmpty()) {
                        events.add(new LlmEvent.TextDelta(delta));
                    }
                    JsonNode toolCalls = choice.path("delta").path("tool_calls");
                    if (toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            String id = tc.path("id").asText(null);
                            String name = tc.path("function").path("name").asText(null);
                            String args = tc.path("function").path("arguments").asText("");
                            if (id != null && name != null) {
                                events.add(new LlmEvent.ToolCall(id, name, args));
                            }
                        }
                    }
                    if (choice.hasNonNull("finish_reason")) {
                        finishReason = choice.get("finish_reason").asText();
                    }
                    JsonNode u = root.path("usage");
                    if (u.has("prompt_tokens")) {
                        usage = new LlmEvent.Usage(u.path("prompt_tokens").asLong(),
                                u.path("completion_tokens").asLong());
                    }
                }
                events.add(new LlmEvent.Finish(finishReason, usage));
                return events.stream();
            }
        } catch (Exception e) {
            return Stream.of(new LlmEvent.Error(e));
        }
    }

    private HttpRequest buildRequest(LlmRequest req) throws Exception {
        ObjectNode body = OM.createObjectNode();
        body.put("model", req.model());
        body.put("stream", true);
        body.put("temperature", req.temperature());
        body.put("messages", buildMessages(req));

        if (!req.tools().isEmpty()) {
            ArrayNode tools = OM.createArrayNode();
            for (ToolSpec t : req.tools()) {
                ObjectNode fn = OM.createObjectNode();
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", OM.valueToTree(t.parameters()));
                ObjectNode tool = OM.createObjectNode();
                tool.put("type", "function");
                tool.set("function", fn);
                tools.add(tool);
            }
            body.set("tools", tools);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(OM.writeValueAsString(body)))
                .build();
    }

    private ArrayNode buildMessages(LlmRequest req) {
        ArrayNode messages = OM.createArrayNode();
        if (req.systemPrompt() != null && !req.systemPrompt().isEmpty()) {
            messages.add(OM.createObjectNode().put("role", "system").put("content", req.systemPrompt()));
        }
        for (ChatMsg m : req.messages()) {
            ObjectNode node = OM.createObjectNode();
            switch (m.role()) {
                case "tool" -> {
                    node.put("role", "tool");
                    node.put("tool_call_id", m.toolCallId());
                    node.put("content", m.toolResult());
                }
                default -> node.put("role", m.role()).put("content", m.content());
            }
            messages.add(node);
        }
        return messages;
    }
}
