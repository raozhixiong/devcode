package com.lobster.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StateDirs {
    public static Path resolve(String override) {
        Path root = (override == null || override.isBlank())
                ? Path.of(System.getProperty("user.home"), ".lobster")
                : Path.of(override);
        try {
            Files.createDirectories(root.resolve("workspace"));
            Files.createDirectories(root.resolve("agents"));
            Files.createDirectories(root.resolve("tool-output"));
            Files.createDirectories(root.resolve("logs"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建状态目录: " + root, e);
        }
        return root;
    }
}
