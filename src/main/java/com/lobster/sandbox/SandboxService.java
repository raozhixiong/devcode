package com.lobster.sandbox;

import java.util.Optional;
import java.util.function.Function;

/**
 * 沙箱服务（M5-25，FR-G-4）：按 mode（off/non-main/all）+ 后端（docker/local）决定命令是否在隔离环境执行。
 * agentId 为 main 且 mode=non-main 时本地执行，其余按 mode 进沙箱。
 */
public class SandboxService {

    public enum Mode { OFF, NON_MAIN, ALL }
    public enum Backend { DOCKER, LOCAL }

    private final Function<String, String> configGet;
    private final SandboxBackend localBackend = new LocalBackend();
    private final SandboxBackend dockerBackend = new DockerBackend();

    public SandboxService(Function<String, String> configGet) {
        this.configGet = configGet;
    }

    public Mode mode() {
        String m = configGet.apply("sandbox.mode");
        if (m == null) return Mode.OFF;
        try { return Mode.valueOf(m.toUpperCase().replace('-', '_')); } catch (Exception e) { return Mode.OFF; }
    }

    public Backend backend() {
        String b = configGet.apply("sandbox.backend");
        if (b == null) return Backend.LOCAL;
        try { return Backend.valueOf(b.toUpperCase()); } catch (Exception e) { return Backend.LOCAL; }
    }

    public String image() {
        String i = configGet.apply("sandbox.image");
        return i == null ? "lobster-sandbox:latest" : i;
    }

    public String workspace() {
        String w = configGet.apply("sandbox.workspace");
        return w == null ? System.getProperty("user.dir") : w;
    }

    public boolean applies(String agentId) {
        return switch (mode()) {
            case OFF -> false;
            case ALL -> true;
            case NON_MAIN -> !"main".equals(agentId);
        };
    }

    /** 若需沙箱则返回输出，否则返回空（调用方本地执行）。 */
    public Optional<String> tryExecute(String agentId, String command, long timeoutMs) throws Exception {
        if (!applies(agentId)) return Optional.empty();
        SandboxBackend b = backend() == Backend.DOCKER ? dockerBackend : localBackend;
        return Optional.of(b.run(image(), workspace(), command, timeoutMs));
    }
}
