package com.lobster.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/** 托管工作树 Worktree（FR-I8）：为每个代理隔离出独立 git 工作树运行。 */
public class WorktreeService {

    private final Function<String, String> cfg;
    private final Path stateDir;

    public WorktreeService(Function<String, String> cfg, Path stateDir) {
        this.cfg = cfg;
        this.stateDir = stateDir;
    }

    public boolean enabled() {
        return "true".equalsIgnoreCase(cfg.apply("worktree.enabled"));
    }

    public Path pathOf(String agentId) {
        return stateDir.resolve("worktrees").resolve(agentId);
    }

    public Path create(String agentId) throws Exception {
        if (!enabled()) throw new IllegalStateException("worktree 未启用（配置 worktree.enabled=true）");
        Path base = pathOf(agentId);
        if (Files.exists(base)) return base;
        Files.createDirectories(stateDir.resolve("worktrees"));
        String branch = "lobster/worktree/" + agentId;
        List<String> cmd = branchExists(branch)
                ? List.of("git", "worktree", "add", "--force", base.toString(), branch)
                : List.of("git", "worktree", "add", "--force", base.toString(), "-b", branch);
        Process p = new ProcessBuilder(cmd).directory(stateDir.toFile()).redirectErrorStream(true).start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("创建 worktree 失败: "
                    + new String(p.getInputStream().readAllBytes()));
        }
        runSetup(base);
        return base;
    }

    private void runSetup(Path base) throws Exception {
        String setup = cfg.apply("worktree.setup_cmd");
        if (setup == null || setup.isBlank()) return;
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = windows
                ? List.of("cmd.exe", "/c", setup)
                : List.of("sh", "-c", setup);
        Process p = new ProcessBuilder(cmd).directory(base.toFile()).redirectErrorStream(true).start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("worktree 初始化命令失败: "
                    + new String(p.getInputStream().readAllBytes()));
        }
    }

    private boolean branchExists(String branch) throws Exception {
        var p = new ProcessBuilder("git", "rev-parse", "--verify", branch)
                .directory(stateDir.toFile()).redirectErrorStream(true).start();
        return p.waitFor() == 0;
    }
}
