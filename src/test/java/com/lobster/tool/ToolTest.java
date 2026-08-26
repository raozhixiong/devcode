package com.lobster.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolTest {
    private final com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void readWriteEditRoundtrip(@TempDir Path tmp) throws Exception {
        var write = new com.lobster.tool.builtin.WriteTool();
        write.execute(om.readTree("{\"file_path\":\"" + slash(tmp) + "/a.txt\",\"content\":\"line1\\nline2\"}"),
                ToolContext.dummy());
        var read = new com.lobster.tool.builtin.ReadTool();
        assertTrue(read.execute(om.readTree("{\"file_path\":\"" + slash(tmp) + "/a.txt\"}"),
                ToolContext.dummy()).output().contains("1: line1"));
        var edit = new com.lobster.tool.builtin.EditTool();
        edit.execute(om.readTree("{\"file_path\":\"" + slash(tmp) + "/a.txt\",\"old_string\":\"line1\",\"new_string\":\"LINE1\"}"),
                ToolContext.dummy());
        assertTrue(read.execute(om.readTree("{\"file_path\":\"" + slash(tmp) + "/a.txt\"}"),
                ToolContext.dummy()).output().contains("LINE1"));
    }

    @Test
    void editFailsWhenOldStringMissing(@TempDir Path tmp) throws Exception {
        var write = new com.lobster.tool.builtin.WriteTool();
        write.execute(om.readTree("{\"file_path\":\"" + slash(tmp) + "/b.txt\",\"content\":\"x\"}"),
                ToolContext.dummy());
        var edit = new com.lobster.tool.builtin.EditTool();
        assertThrows(Exception.class, () -> edit.execute(
                om.readTree("{\"file_path\":\"" + slash(tmp) + "/b.txt\",\"old_string\":\"nope\",\"new_string\":\"y\"}"),
                ToolContext.dummy()));
    }

    @Test
    void globAndGrep(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Foo.java"), "class Foo {}");
        Files.writeString(tmp.resolve("Bar.java"), "class Bar {}");
        var glob = new com.lobster.tool.builtin.GlobTool();
        assertTrue(glob.execute(om.readTree("{\"pattern\":\"**/*.java\",\"path\":\"" + slash(tmp) + "\"}"),
                ToolContext.dummy()).output().contains("Foo.java"));
        var grep = new com.lobster.tool.builtin.GrepTool();
        assertTrue(grep.execute(om.readTree("{\"pattern\":\"class Foo\",\"path\":\"" + slash(tmp) + "\"}"),
                ToolContext.dummy()).output().contains("Foo.java"));
    }

    @Test
    void bashRunsCommand() throws Exception {
        var bash = new com.lobster.tool.builtin.BashTool();
        var out = bash.execute(om.readTree("{\"command\":\"echo lobster\"}"), ToolContext.dummy());
        assertTrue(out.output().contains("lobster"));
    }

    @Test
    void readTruncates(@TempDir Path tmp) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 2500; i++) sb.append("line").append(i).append("\n");
        Files.writeString(tmp.resolve("big.txt"), sb.toString());
        var out = new com.lobster.tool.builtin.ReadTool()
                .execute(om.readTree("{\"file_path\":\"" + slash(tmp) + "/big.txt\"}"), ToolContext.dummy());
        assertTrue(out.output().contains("truncated"));
    }

    private static String slash(Path p) { return p.toString().replace("\\", "/"); }
}
