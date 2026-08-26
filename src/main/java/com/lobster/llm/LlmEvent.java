package com.lobster.llm;

/** 统一 LLM 事件（归一化自各 provider 流）。 */
public sealed interface LlmEvent permits LlmEvent.TextDelta, LlmEvent.ToolCall, LlmEvent.Finish, LlmEvent.Error {

    record TextDelta(String text) implements LlmEvent {}

    record ToolCall(String callId, String name, String argumentsJson) implements LlmEvent {}

    record Finish(String reason, Usage usage) implements LlmEvent {}

    record Error(Throwable cause) implements LlmEvent {}

    record Usage(long inputTokens, long outputTokens) {}
}
