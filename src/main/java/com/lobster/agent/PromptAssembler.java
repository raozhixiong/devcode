package com.lobster.agent;

import com.lobster.llm.LlmProvider;

import java.util.List;

/** M1 简化提示词组装器：身份段 + env 块 + 工具清单。 */
public class PromptAssembler {

    private final String agentId;
    private final String model;

    public PromptAssembler(String agentId, String model) {
        this.agentId = agentId;
        this.model = model;
    }

    public String assemble(List<LlmProvider.ToolSpec> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是龙虾工作台的智能代理（").append(agentId).append("），帮助用户完成软件工程任务。\n\n");
        sb.append("<env>\n");
        sb.append("Model: ").append(model).append('\n');
        sb.append("Platform: ").append(System.getProperty("os.name")).append('\n');
        sb.append("Working directory: ").append(System.getProperty("user.dir")).append('\n');
        sb.append("Today's date: ").append(java.time.LocalDate.now()).append('\n');
        sb.append("</env>\n\n");
        if (!tools.isEmpty()) {
            sb.append("可用工具：")
              .append(String.join(", ", tools.stream().map(LlmProvider.ToolSpec::name).toList()))
              .append('\n');
            sb.append("使用工具时给出明确的参数；完成后给出简明总结。\n");
        }
        return sb.toString();
    }
}
