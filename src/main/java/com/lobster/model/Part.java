package com.lobster.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
public sealed interface Part permits
        Part.Text, Part.Reasoning, Part.Tool, Part.File,
        Part.StepFinish, Part.Snapshot, Part.Compaction, Part.Synthetic {

    record Text(String text, boolean synthetic, boolean ignored) implements Part {}

    record Reasoning(String text) implements Part {}

    record Tool(String tool, String callId, ToolState state) implements Part {}

    record File(String mime, String filename, String url) implements Part {}

    record StepFinish(String reason, long inputTokens, long outputTokens, double cost) implements Part {}

    record Snapshot(String hash, List<String> files) implements Part {}

    record Compaction(boolean auto, String summary) implements Part {}

    record Synthetic(String text) implements Part {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
    sealed interface ToolState permits ToolState.Pending, ToolState.Running,
            ToolState.Completed, ToolState.Error {
        record Pending(String rawInput) implements ToolState {}
        record Running(String title, Map<String, Object> metadata) implements ToolState {}
        record Completed(String title, String output, Map<String, Object> metadata) implements ToolState {}
        record Error(String error) implements ToolState {}
    }
}
