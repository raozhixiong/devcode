package com.lobster.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Skills loader (FR-F-7): scan stateDir/skills for SKILL.md files.
 * Skill format: SKILL.md (frontmatter + content), enabled flag via .disabled file.
 */
public class SkillsStore {

    private static final Logger log = LoggerFactory.getLogger(SkillsStore.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final Path skillsRoot;
    private final EventBus bus;

    public SkillsStore(Path stateDir, EventBus bus) {
        this.skillsRoot = stateDir.resolve("skills");
        this.bus = bus;
        try {
            Files.createDirectories(skillsRoot);
        } catch (IOException e) {
            log.warn("Cannot create skills dir: {}", e.getMessage());
        }
    }

    public record Skill(String name, String description, String content, boolean enabled) {}

    /** List all skills. */
    public List<Skill> list() {
        List<Skill> skills = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(skillsRoot)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                Path skillFile = dir.resolve("SKILL.md");
                if (!Files.exists(skillFile)) continue;
                String name = dir.getFileName().toString();
                try {
                    String content = Files.readString(skillFile);
                    String desc = extractDescription(content);
                    boolean enabled = !Files.exists(dir.resolve(".disabled"));
                    skills.add(new Skill(name, desc, content, enabled));
                } catch (IOException e) {
                    log.warn("Read skill {} failed: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描 skills 目录failed: {}", e.getMessage());
        }
        return skills;
    }

    /** Get single skill. */
    public Optional<Skill> get(String name) {
        Path skillFile = skillsRoot.resolve(name).resolve("SKILL.md");
        if (!Files.exists(skillFile)) return Optional.empty();
        try {
            String content = Files.readString(skillFile);
            String desc = extractDescription(content);
            boolean enabled = !Files.exists(skillsRoot.resolve(name).resolve(".disabled"));
            return Optional.of(new Skill(name, desc, content, enabled));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Enable/disable skill. */
    public boolean setEnabled(String name, boolean enabled) {
        Path dir = skillsRoot.resolve(name);
        if (!Files.isDirectory(dir)) return false;
        Path disabled = dir.resolve(".disabled");
        try {
            if (enabled) {
                Files.deleteIfExists(disabled);
            } else {
                Files.createFile(disabled);
            }
            publishChanged(name, enabled ? "enabled" : "disabled");
            return true;
        } catch (IOException e) {
            log.warn("Toggle skill {} 状态failed: {}", name, e.getMessage());
            return false;
        }
    }

    /** Install skill (write SKILL.md). */
    public Skill install(String name, String content) {
        try {
            Path dir = skillsRoot.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), content);
            publishChanged(name, "installed");
            return get(name).orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException("安装技能failed: " + name, e);
        }
    }

    /** List enabled skill names (for prompt injection). */
    public List<String> enabledNames() {
        return list().stream().filter(Skill::enabled).map(Skill::name).toList();
    }

    private String extractDescription(String content) {
        String firstLine = content.stripLeading();
        int newline = firstLine.indexOf('\n');
        String first = newline > 0 ? firstLine.substring(0, newline) : firstLine;
        if (first.startsWith("# ")) return first.substring(2).strip();
        if (first.startsWith("<!-- description:")) {
            int end = first.indexOf("-->");
            if (end > 0) return first.substring(17, end).strip();
        }
        return first.length() > 80 ? first.substring(0, 80) : first;
    }

    private void publishChanged(String skillName, String kind) {
        ObjectNode data = OM.createObjectNode().put("skill", skillName).put("kind", kind);
        bus.publish(new LobsterEvent(Events.SKILLS_CHANGED, "", data, false));
    }
}
