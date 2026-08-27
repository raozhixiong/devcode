package com.lobster.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** MCP stdio 传输：启动子进程，stdin/stdout 按行交换 JSON-RPC。 */
public class StdioTransport implements McpTransport {

    private final Process proc;
    private final BufferedWriter out;
    private final BufferedReader in;

    public StdioTransport(String command, List<String> args, Map<String, String> env) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(command);
        if (args != null) pb.command().addAll(args);
        if (env != null) pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        proc = pb.start();
        out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public void send(String jsonLine) throws IOException {
        synchronized (out) {
            out.write(jsonLine);
            out.write('\n');
            out.flush();
        }
    }

    @Override
    public String receive() throws IOException {
        return in.readLine();
    }

    @Override
    public void close() {
        try { out.close(); } catch (IOException ignored) {}
        try { in.close(); } catch (IOException ignored) {}
        proc.destroyForcibly();
    }
}
