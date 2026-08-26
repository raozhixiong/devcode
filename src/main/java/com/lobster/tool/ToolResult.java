package com.lobster.tool;

import com.lobster.model.Part;

import java.util.List;

public record ToolResult(String title, String output, List<Part.File> attachments) {

    public static ToolResult of(String title, String output) {
        return new ToolResult(title, output, List.of());
    }
}
