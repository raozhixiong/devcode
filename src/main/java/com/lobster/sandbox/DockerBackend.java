package com.lobster.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Docker 后端：docker run 隔离容器执行（sandbox.backend=docker）。 */
public class DockerBackend implements SandboxBackend {

    @Override
    public String run(String image, String workspace, String command, long timeoutMs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm",
                "-v", workspace + ":/work",
                "-w", "/work",
                image, "sh", "-c", command);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = proc.getInputStream()) { in.transferTo(buffer); } catch (Exception ignored) {}
        });
        boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return "Error: docker sandbox timed out after " + timeoutMs + "ms";
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
