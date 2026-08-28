package com.lobster.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 本地后端：直接在网关进程内执行（测试安全后端，亦可作为 sandbox.backend=local）。 */
public class LocalBackend implements SandboxBackend {

    @Override
    public String run(String image, String workspace, String command, long timeoutMs) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = new ProcessBuilder(windows ? new String[]{"cmd", "/c", command}
                : new String[]{"sh", "-c", command});
        if (workspace != null) pb.directory(new File(workspace));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = proc.getInputStream()) { in.transferTo(buffer); } catch (Exception ignored) {}
        });
        boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return "Error: sandbox timed out after " + timeoutMs + "ms";
        }
        reader.join(2000);
        return truncate(buffer);
    }

    private static String truncate(ByteArrayOutputStream buffer) {
        byte[] bytes = buffer.toByteArray();
        if (bytes.length <= 50 * 1024) return new String(bytes, StandardCharsets.UTF_8);
        return new String(bytes, 0, 50 * 1024, StandardCharsets.UTF_8) + "\n...output truncated at 50KB...";
    }
}
