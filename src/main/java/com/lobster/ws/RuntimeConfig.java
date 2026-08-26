package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lobster.agent.AgentLoop;
import com.lobster.event.EventBus;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Message;
import com.lobster.model.Part;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.tool.ToolRegistry;
import com.lobster.tool.builtin.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/** 运行时装配：默认 main agent（M1 单 agent，Mock LLM）。 */
@Configuration
public class RuntimeConfig {

    @Bean
    public AgentDb mainAgentDb(Path stateDir) {
        return AgentDb.open(stateDir.resolve("agents"), "main");
    }

    @Bean
    public MessageStore messageStore(AgentDb mainAgentDb) {
        return new MessageStore(mainAgentDb);
    }

    @Bean
    public EventBus eventBus(AgentDb mainAgentDb) {
        return new EventBus(mainAgentDb);
    }

    @Bean
    public AgentLoop agentLoop(MessageStore store, EventBus bus) {
        var tools = ToolRegistry.of(
                new ReadTool(), new WriteTool(), new EditTool(),
                new GlobTool(), new GrepTool(), new BashTool(),
                new TodoTool(), new QuestionTool(), new ListTool());
        var permissions = new PermissionEngine(List.of(
                new PermissionRule("read", "*", PermissionRule.Action.ALLOW),
                new PermissionRule("glob", "*", PermissionRule.Action.ALLOW),
                new PermissionRule("grep", "*", PermissionRule.Action.ALLOW),
                new PermissionRule("ls", "*", PermissionRule.Action.ALLOW),
                new PermissionRule("todo", "*", PermissionRule.Action.ALLOW),
                new PermissionRule("write", "*", PermissionRule.Action.ASK),
                new PermissionRule("edit", "*", PermissionRule.Action.ASK),
                new PermissionRule("bash", "echo *", PermissionRule.Action.ALLOW),
                new PermissionRule("bash", "dir *", PermissionRule.Action.ALLOW),
                new PermissionRule("bash", "*", PermissionRule.Action.ASK),
                new PermissionRule("question", "*", PermissionRule.Action.ALLOW),
                new PermissionRule("task", "*", PermissionRule.Action.ALLOW)
        ), null);
        // M1 演示用 Mock LLM；接入真实模型时替换为 OpenAiCompatProvider
        var llm = new com.lobster.llm.MockLlmProvider(List.of(
                new com.lobster.llm.LlmEvent.TextDelta(
                        "（Mock 模式）已收到消息。配置真实 LLM 后可执行完整工具循环。"),
                new com.lobster.llm.LlmEvent.Finish("stop",
                        new com.lobster.llm.LlmEvent.Usage(10, 20))));
        return new AgentLoop(store, bus, tools, permissions, llm, "main", "mock-echo");
    }
}
