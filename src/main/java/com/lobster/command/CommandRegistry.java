package com.lobster.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令注册表（FR-I2）：slash 命令 / 键绑定 / 程序调用 三位一体同源注册。
 * 来源：内置 + 插件贡献 + workspace `.lobster/commands`/*.json。
 */
public class CommandRegistry {

    private static final ObjectMapper OM = new ObjectMapper();

    public record Command(String id, String title, String category, String slashName,
                          String description, String source) {}

    public static CommandRegistry builtin() {
        var r = new CommandRegistry();
        r.register(new Command("session.clear", "清空会话上下文", "session", "/clear", "清空当前会话消息", "builtin"));
        r.register(new Command("help", "帮助", "general", "/help", "列出可用命令与工具", "builtin"));
        r.register(new Command("skills.list", "列出技能", "skills", "/skills", "列出可用技能", "builtin"));
        return r;
    }

    private final Map<String, Command> byId = new LinkedHashMap<>();

    public synchronized void register(Command cmd) {
        byId.put(cmd.id(), cmd);
    }

    public Command get(String id) { return byId.get(id); }

    public synchronized List<Command> list() { return new ArrayList<>(byId.values()); }

    public synchronized Command bySlash(String slash) {
        String s = slash.startsWith("/") ? slash : "/" + slash;
        for (Command c : byId.values()) {
            if (s.equals(c.slashName())) return c;
        }
        return null;
    }

    /** 从 workspace `.lobster/commands` 加载命令定义并注册。 */
    public synchronized void loadWorkspace(Path stateDir) {
        Path dir = stateDir.resolve(".lobster").resolve("commands");
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                try {
                    JsonNode n = OM.readTree(Files.readString(f));
                    register(new Command(
                            n.path("id").asText(),
                            n.path("title").asText(n.path("id").asText()),
                            n.path("category").asText("workspace"),
                            n.path("slashName").asText(),
                            n.path("description").asText(""),
                            "workspace"));
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }
}
