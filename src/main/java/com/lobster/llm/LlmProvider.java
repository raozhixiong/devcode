package com.lobster.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** LLM 供应商 SPI。 */
public interface LlmProvider {

    /** 发起流式请求，返回事件流（懒惰消费）。 */
    Stream<LlmEvent> stream(LlmRequest req);

    /** 单条对话消息。 */
    record ChatMsg(String role, String content, String toolCallId, String toolName, String toolResult) {
        public static ChatMsg user(String content) { return new ChatMsg("user", content, null, null, null); }
        public static ChatMsg assistant(String content) { return new ChatMsg("assistant", content, null, null, null); }
        public static ChatMsg toolResult(String toolCallId, String toolName, String result) {
            return new ChatMsg("tool", result, toolCallId, toolName, result);
        }
    }

    /** 工具规格（发给 LLM 的 JSON Schema）。 */
    record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    record LlmRequest(
            String model,
            String systemPrompt,
            List<ChatMsg> messages,
            List<ToolSpec> tools,
            double temperature) {}
}
