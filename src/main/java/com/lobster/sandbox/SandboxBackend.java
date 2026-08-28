package com.lobster.sandbox;

/** 沙箱后端：在隔离环境中执行命令并返回输出文本。 */
public interface SandboxBackend {
    String run(String image, String workspace, String command, long timeoutMs) throws Exception;
}
