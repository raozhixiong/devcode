package com.lobster.agent;

import com.lobster.llm.LlmProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 提示词组装器：身份段 + AGENTS.md（向上查找）+ env 块 + 工具清单。
 * cache 边界：前缀（system+AGENTS.md+env+tools）稳定，动态内容靠后。
 */
public class PromptAssembler {

    private static final int MAX_AGENTS_MD = 16 * 1024;

    private final String agentId;
    private final String model;

    public PromptAssembler(String agentId, String model) {
        this.agentId = agentId;
        this.model = model;
    }

    public String assemble(List<LlmProvider.ToolSpec> tools) {
        return assemble(tools, Path.of(System.getProperty("user.dir")), List.of(), List.of());
    }

    public String assemble(List<LlmProvider.ToolSpec> tools, Path workingDir) {
        return assemble(tools, workingDir, List.of(), List.of());
    }

    public String assemble(List<LlmProvider.ToolSpec> tools, Path workingDir,
                           List<String> skillNames, List<String> referenceNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是龙虾工作台的智能代理（").append(agentId).append("），帮助用户完成软件工程任务。\n\n");

        String agents = findAgentsMd(workingDir);
        if (agents != null) {
            sb.append("<project-instructions>\n").append(agents).append("\n</project-instructions>\n\n");
        }

        sb.append("<env>\n");
        sb.append("Model: ").append(model).append('\n');
        sb.append("Platform: ").append(System.getProperty("os.name")).append('\n');
        sb.append("Working directory: ").append(workingDir).append('\n');
        sb.append("Today's date: ").append(java.time.LocalDate.now()).append('\n');
        sb.append("</env>\n\n");

        if (!tools.isEmpty()) {
            sb.append("可用工具：")
              .append(String.join(", ", tools.stream().map(LlmProvider.ToolSpec::name).toList()))
              .append('\n');
            sb.append("使用工具时给出明确的参数；完成后给出简明总结。\n");
        }
        if (skillNames != null && !skillNames.isEmpty()) {
            sb.append("\n可用技能（用 skill 工具加载其指令）：")
              .append(String.join(", ", skillNames)).append('\n');
        }
        if (referenceNames != null && !referenceNames.isEmpty()) {
            sb.append("\n可用参考库（可在回答时引用）：")
              .append(String.join(", ", referenceNames)).append('\n');
        }
        return sb.toString();
    }

    /** 从工作目录向上查找 AGENTS.md（含根），返回首个命中的内容。 */
    static String findAgentsMd(Path from) {
        Path dir = from.toAbsolutePath().normalize();
        while (true) {
            try {
                Path f = dir.resolve("AGENTS.md");
                if (Files.isRegularFile(f)) {
                    String content = Files.readString(f);
                    if (content.length() > MAX_AGENTS_MD) {
                        content = content.substring(0, MAX_AGENTS_MD) + "\n...truncated...";
                    }
                    return content;
                }
            } catch (Exception ignored) {}
            Path parent = dir.getParent();
            if (parent == null) return null;
            dir = parent;
        }
    }
}
