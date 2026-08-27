package com.lobster.mcp;

import java.io.IOException;

/** MCP 传输层（JSON-RPC 2.0，按行帧）。默认 stdio 实现见 StdioTransport。 */
public interface McpTransport extends AutoCloseable {

    /** 发送一行 JSON-RPC 消息。 */
    void send(String jsonLine) throws IOException;

    /** 阻塞读取一行 JSON-RPC 消息。 */
    String receive() throws IOException;

    @Override void close();
}
