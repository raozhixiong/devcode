package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Shell 命令执行。Windows: cmd /c；其他: sh -c。默认超时 120s。 */
public class BashTool implements Tool {

    static final long DEFAULT_TIMEOUT_MS = 120_000;

    private final com.lobster.sandbox.SandboxService sandbox;

    public BashTool() { this(null); }

    public BashTool(com.lobster.sandbox.SandboxService sandbox) { this.sandbox = sandbox; }

    @Override public String id() { return "bash"; }

    @Override public String description() {
        return "Execute a shell command and return stdout+stderr. Default timeout 120 seconds. "
                + "Use for builds, tests, git, and file inspection.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string"),
                        "timeout_ms", Map.of("type", "integer")),
                "required", List.of("command"));
    }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        String command = args.get("command").asText();
        long timeout = args.hasNonNull("timeout_ms") ? args.get("timeout_ms").asLong() : DEFAULT_TIMEOUT_MS;

        if (sandbox != null) {
            var sandboxed = sandbox.tryExecute(ctx.agentId(), command, timeout);
            if (sandboxed.isPresent()) {
                String out = sandboxed.get();
                return ToolResult.of(command, out.isEmpty() ? "(no output)" : out);
            }
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = new ProcessBuilder(windows ? new String[]{"cmd", "/c", command}
                : new String[]{"sh", "-c", command});
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = proc.getInputStream()) {
                in.transferTo(buffer);
            } catch (Exception ignored) {}
        });
        boolean finished = proc.waitFor(timeout, TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return ToolResult.of(command, "Error: timed out after " + timeout + "ms\n"
                    + truncate(buffer));
        }
        reader.join(2000);
        String output = truncate(buffer);
        if (proc.exitValue() != 0) {
            return ToolResult.of(command, output + "\n(exit code " + proc.exitValue() + ")");
        }
        return ToolResult.of(command, output.isEmpty() ? "(no output)" : output);
    }

    private static String truncate(ByteArrayOutputStream buffer) {
        byte[] bytes = buffer.toByteArray();
        if (bytes.length <= ReadTool.MAX_BYTES) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, 0, ReadTool.MAX_BYTES, StandardCharsets.UTF_8)
                + "\n...output truncated at 50KB...";
    }
}
