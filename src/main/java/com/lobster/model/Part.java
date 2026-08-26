package com.lobster.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Part.Text.class, name = "text"),
        @JsonSubTypes.Type(value = Part.Reasoning.class, name = "reasoning"),
        @JsonSubTypes.Type(value = Part.Tool.class, name = "tool"),
        @JsonSubTypes.Type(value = Part.File.class, name = "file"),
        @JsonSubTypes.Type(value = Part.StepFinish.class, name = "step-finish"),
        @JsonSubTypes.Type(value = Part.Snapshot.class, name = "snapshot"),
        @JsonSubTypes.Type(value = Part.Compaction.class, name = "compaction"),
        @JsonSubTypes.Type(value = Part.Synthetic.class, name = "synthetic")
})
public sealed interface Part permits
        Part.Text, Part.Reasoning, Part.Tool, Part.File,
        Part.StepFinish, Part.Snapshot, Part.Compaction, Part.Synthetic {

    @JsonTypeName("text")
    record Text(String text, boolean synthetic, boolean ignored) implements Part {}

    @JsonTypeName("reasoning")
    record Reasoning(String text) implements Part {}

    @JsonTypeName("tool")
    record Tool(String tool, String callId, ToolState state) implements Part {}

    @JsonTypeName("file")
    record File(String mime, String filename, String url) implements Part {}

    @JsonTypeName("step-finish")
    record StepFinish(String reason, long inputTokens, long outputTokens, double cost) implements Part {}

    @JsonTypeName("snapshot")
    record Snapshot(String hash, List<String> files) implements Part {}

    @JsonTypeName("compaction")
    record Compaction(boolean auto, String summary) implements Part {}

    @JsonTypeName("synthetic")
    record Synthetic(String text) implements Part {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ToolState.Pending.class, name = "pending"),
            @JsonSubTypes.Type(value = ToolState.Running.class, name = "running"),
            @JsonSubTypes.Type(value = ToolState.Completed.class, name = "completed"),
            @JsonSubTypes.Type(value = ToolState.Error.class, name = "error")
    })
    sealed interface ToolState permits ToolState.Pending, ToolState.Running,
            ToolState.Completed, ToolState.Error {

        @JsonTypeName("pending")
        record Pending(String rawInput) implements ToolState {}

        @JsonTypeName("running")
        record Running(String title, Map<String, Object> metadata) implements ToolState {}

        @JsonTypeName("completed")
        record Completed(String title, String output, Map<String, Object> metadata) implements ToolState {}

        @JsonTypeName("error")
        record Error(String error) implements ToolState {}
    }
}
