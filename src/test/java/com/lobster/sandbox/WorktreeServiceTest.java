package com.lobster.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeServiceTest {

    @TempDir Path repo;

    private void gitInit() throws Exception {
        run(List.of("git", "init"));
        run(List.of("git", "config", "user.email", "t@t"));
        run(List.of("git", "config", "user.name", "t"));
        Files.writeString(repo.resolve("README.md"), "x");
        run(List.of("git", "add", "."));
        run(List.of("git", "commit", "-m", "init"));
    }

    private void run(List<String> cmd) throws Exception {
        var p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        int c = p.waitFor();
        if (c != 0) throw new IllegalStateException(new String(p.getInputStream().readAllBytes()));
    }

    @Test
    void disabledByDefault() {
        var svc = new WorktreeService(p -> null, repo);
        assertFalse(svc.enabled());
    }

    @Test
    void createWorktree() throws Exception {
        gitInit();
        var svc = new WorktreeService(p -> p.equals("worktree.enabled") ? "true" : "exit 0", repo);
        assertTrue(svc.enabled());
        Path wt = svc.create("agentA");
        assertTrue(Files.exists(wt));
        assertTrue(Files.exists(repo.resolve(".git").resolve("worktrees")));
    }
}
