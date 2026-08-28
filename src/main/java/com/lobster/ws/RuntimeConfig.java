package com.lobster.ws;

import com.lobster.agent.AgentLoop;
import com.lobster.config.LobsterConfig;
import com.lobster.event.EventBus;
import com.lobster.llm.LlmEvent;
import com.lobster.llm.LlmProvider;
import com.lobster.llm.MockLlmProvider;
import com.lobster.llm.OpenAiCompatProvider;
import com.lobster.permission.PermissionEngine;
import com.lobster.permission.PermissionRule;
import com.lobster.store.AgentDb;
import com.lobster.store.MessageStore;
import com.lobster.store.ShareService;
import com.lobster.tool.PermissionReply;
import com.lobster.tool.ToolRegistry;
import com.lobster.tool.builtin.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 运行时装配：lobster.json 配置驱动，无配置回退 Mock。 */
@Configuration
public class RuntimeConfig {

    @Bean
    public LobsterConfig lobsterConfig(Path stateDir, @Value("${lobster.config:}") String configOverride) {
        return new LobsterConfig(stateDir, configOverride);
    }

    @Bean
    public AgentDb mainAgentDb(Path stateDir) {
        return AgentDb.open(stateDir.resolve("agents"), "main");
    }

    @Bean
    public com.lobster.rbac.AgentRegistry agentRegistry(javax.sql.DataSource sharedDataSource, Path stateDir) {
        return new com.lobster.rbac.AgentRegistry(new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), stateDir);
    }

    @Bean
    public com.lobster.store.SessionOwnership sessionOwnership(AgentDb mainAgentDb) {
        return new com.lobster.store.SessionOwnership(mainAgentDb);
    }

    @Bean
    public com.lobster.store.SessionStateService sessionStateService(AgentDb mainAgentDb, EventBus bus) {
        return new com.lobster.store.SessionStateService(mainAgentDb, bus);
    }

    @Bean
    public com.lobster.store.TaskStore taskStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.TaskStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.WorkboardStore workboardStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.WorkboardStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.CronStore cronStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.CronStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.CronScheduler cronScheduler(com.lobster.store.CronStore cronStore,
                                                          MessageStore messageStore,
                                                          AgentLoop loop,
                                                          EventBus bus) {
        return new com.lobster.store.CronScheduler(cronStore, messageStore, loop, bus);
    }

    @Bean
    public com.lobster.store.MemoryStore memoryStore(AgentDb mainAgentDb, Path stateDir) {
        return new com.lobster.store.MemoryStore(mainAgentDb,
                stateDir.resolve("agents").resolve("main").resolve("workspace"));
    }

    @Bean
    public com.lobster.store.DreamingSweep dreamingSweep(com.lobster.store.MemoryStore memoryStore) {
        return new com.lobster.store.DreamingSweep(memoryStore);
    }

    @Bean
    public com.lobster.store.UsageStore usageStore(AgentDb mainAgentDb) {
        return new com.lobster.store.UsageStore(mainAgentDb);
    }

    @Bean
    public com.lobster.store.SkillsStore skillsStore(Path stateDir, EventBus bus) {
        return new com.lobster.store.SkillsStore(stateDir, bus);
    }

    @Bean
    public com.lobster.store.UserStore userStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.UserStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.AuthTokenStore authTokenStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.AuthTokenStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.DeviceStore deviceStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.DeviceStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.auth.AuthService authService(com.lobster.store.UserStore userStore,
                                                     com.lobster.store.AuthTokenStore authTokenStore,
                                                     com.lobster.store.DeviceStore deviceStore) {
        return new com.lobster.auth.AuthService(userStore, authTokenStore, deviceStore);
    }

    @Bean
    public com.lobster.store.AuditStore auditStore(javax.sql.DataSource sharedDataSource) {
        return new com.lobster.store.AuditStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource));
    }

    @Bean
    public com.lobster.store.ApprovalStore approvalStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.ApprovalStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.ChannelStore channelStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.ChannelStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.ConfigStore configStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.ConfigStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.PluginStore pluginStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.PluginStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.PluginMarketplace pluginMarketplace() {
        return new com.lobster.store.PluginMarketplace();
    }

    @Bean
    public com.lobster.store.HookStore hookStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.HookStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.agent.HookEngine hookEngine(com.lobster.store.HookStore hookStore, EventBus bus) {
        return new com.lobster.agent.HookEngine(hookStore, bus);
    }

    @Bean
    public com.lobster.sandbox.SandboxService sandboxService(com.lobster.store.ConfigStore configStore) {
        return new com.lobster.sandbox.SandboxService(p -> configStore.getValue(p).orElse(null));
    }

    @Bean
    public com.lobster.command.CommandRegistry commandRegistry(Path stateDir) {
        var reg = com.lobster.command.CommandRegistry.builtin();
        reg.loadWorkspace(stateDir);
        return reg;
    }

    @Bean
    public com.lobster.store.IntegrationStore integrationStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.IntegrationStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.ReferenceStore referenceStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.ReferenceStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.store.ReferenceLoader referenceLoader(com.lobster.store.ConfigStore configStore, Path stateDir) {
        return new com.lobster.store.ReferenceLoader(p -> configStore.getValue(p).orElse(null));
    }

    @Bean
    public com.lobster.tool.builtin.ReferenceTool referenceTool(com.lobster.store.ReferenceStore referenceStore,
                                                                com.lobster.store.ReferenceLoader referenceLoader, Path stateDir) {
        return new com.lobster.tool.builtin.ReferenceTool(referenceStore, referenceLoader, stateDir);
    }

    @Bean
    public com.lobster.command.CommandExecutor commandExecutor(MessageStore messageStore,
                                                                AgentLoop agentLoop, ShareService shareService) {
        return new com.lobster.command.CommandExecutor(messageStore, agentLoop, shareService);
    }

    @Bean
    public com.lobster.store.ArtifactsStore artifactsStore(javax.sql.DataSource sharedDataSource, EventBus bus) {
        return new com.lobster.store.ArtifactsStore(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), bus);
    }

    @Bean
    public com.lobster.tool.builtin.ComputerBackend computerBackend(com.lobster.store.ConfigStore configStore) {
        return new com.lobster.tool.builtin.LocalComputerBackend(
                p -> configStore.getValue(p).orElse(null));
    }

    @Bean
    public com.lobster.tool.builtin.ComputerTool computerTool(com.lobster.tool.builtin.ComputerBackend backend) {
        return new com.lobster.tool.builtin.ComputerTool(backend);
    }

    @Bean
    public com.lobster.tool.builtin.TtsTool ttsTool(Path stateDir) {
        var cfg = (java.util.function.Function<String, String>) k -> System.getProperty("lobster.tts." + k);
        return new com.lobster.tool.builtin.TtsTool(stateDir, cfg);
    }

    @Bean
    public com.lobster.store.ShareService shareService(javax.sql.DataSource sharedDataSource,
                                                       MessageStore messageStore, EventBus bus) {
        return new com.lobster.store.ShareService(
                new org.springframework.jdbc.core.JdbcTemplate(sharedDataSource), messageStore, bus);
    }

    @Bean
    public com.lobster.sandbox.WorktreeService worktreeService(com.lobster.store.ConfigStore configStore, Path stateDir) {
        return new com.lobster.sandbox.WorktreeService(p -> configStore.getValue(p).orElse(null), stateDir);
    }

    @Bean
    public com.lobster.mcp.McpManager mcpManager(LobsterConfig config) {
        var mgr = new com.lobster.mcp.McpManager();
        var log = org.slf4j.LoggerFactory.getLogger(RuntimeConfig.class);
        for (var s : config.mcpServers()) {
            if (!"stdio".equals(s.transport())) continue;
            try {
                var transport = new com.lobster.mcp.StdioTransport(s.command(), s.args(), s.env());
                var t = Thread.ofVirtual().start(() -> {
                    try {
                        mgr.connect(s.name(), transport);
                    } catch (Exception e) {
                        log.warn("MCP 服务器 {} 连接失败: {}", s.name(), e.getMessage());
                    }
                });
                t.join(15_000);
            } catch (Exception e) {
                log.warn("MCP 服务器 {} 启动失败: {}", s.name(), e.getMessage());
            }
        }
        return mgr;
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
    public PermissionEngine permissionEngine(EventBus bus, LobsterConfig config) {
        List<PermissionRule> rules = new ArrayList<>(List.of(
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
        ));
        // lobster.json permissions 段覆盖（append，findLast 语义即后写的优先）
        for (var r : config.permissionRules()) {
            rules.add(new PermissionRule(r.permission(), r.pattern(), PermissionRule.Action.valueOf(r.action())));
        }
        // ask 挂起时发 permission.asked 事件（带 sessionId 路由）
        return new PermissionEngine(rules, p -> bus.publish(new com.lobster.event.LobsterEvent(
                com.lobster.event.Events.PERMISSION_ASKED, p.request().sessionId(),
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("requestId", p.requestId())
                        .put("permission", p.request().permission())
                        .set("patterns", new com.fasterxml.jackson.databind.ObjectMapper()
                                .valueToTree(p.request().patterns())), false)));
    }

    @Bean
    public com.lobster.store.InboxStore inboxStore(AgentDb mainAgentDb) {
        return new com.lobster.store.InboxStore(mainAgentDb);
    }

    @Bean
    public AgentLoop agentLoop(MessageStore store, EventBus bus, PermissionEngine permissions,
                                LobsterConfig config, com.lobster.store.InboxStore inbox,
                                AgentDb mainAgentDb, com.lobster.store.TaskStore taskStore,
                                com.lobster.store.MemoryStore memoryStore,
                                com.lobster.store.AuditStore auditStore,
                                com.lobster.store.SkillsStore skillsStore,
                                com.lobster.agent.HookEngine hookEngine,
                                com.lobster.mcp.McpManager mcpManager,
                                com.lobster.sandbox.SandboxService sandboxService,
                                com.lobster.store.ReferenceStore referenceStore,
                                com.lobster.tool.builtin.ComputerTool computerTool,
                                com.lobster.tool.builtin.ReferenceTool referenceTool,
                                com.lobster.tool.builtin.TtsTool ttsTool) {
        var tools = ToolRegistry.of(
                new ReadTool(), new WriteTool(), new EditTool(),
                new GlobTool(), new GrepTool(), new BashTool(sandboxService),
                new TodoTool(), new QuestionTool(), new ListTool());
        tools.register(new com.lobster.tool.builtin.SkillTool(skillsStore));
        for (var mt : mcpManager.tools()) tools.register(mt);
        LlmProvider llm;
        String model;
        if (config.hasRealLlm()) {
            var s = config.llm();
            llm = new OpenAiCompatProvider(s.baseUrl(), s.apiKey());
            model = s.model();
        } else {
            llm = new MockLlmProvider(List.of(
                    new LlmEvent.TextDelta("（Mock 模式）已收到消息。在 ~/.lobster/lobster.json 配置 llm 段（baseUrl/apiKey/model）即可接入真实模型。"),
                    new LlmEvent.Finish("stop", new LlmEvent.Usage(10, 20))));
            model = "mock-echo";
        }
        var loop = new AgentLoop(store, bus, tools, permissions, llm, "main", model, inbox);
        // writer claim 围栏（崩溃恢复：重启清孤儿 claim）
        loop.setWriterClaimStore(new com.lobster.store.WriterClaimStore(mainAgentDb));
        // task 子代理工具需引用 loop（子会话复用同一 loop 实例）
        tools.register(new com.lobster.tool.builtin.TaskTool(store, loop));
        tools.register(new com.lobster.tool.builtin.PlanExitTool(loop.planMode()));
        tools.register(new com.lobster.tool.builtin.BackgroundSpawnTool(store, bus, loop, taskStore));
        tools.register(new com.lobster.tool.builtin.MemorySearchTool(memoryStore));
        tools.register(computerTool);
        tools.register(referenceTool);
        tools.register(ttsTool);
        loop.setMemoryStore(memoryStore);
        loop.setAuditStore(auditStore);
        loop.setSkillNames(skillsStore.enabledNames());
        loop.setReferenceNames(referenceStore.enabled().stream()
                .map(com.lobster.store.ReferenceStore.Reference::name).toList());
        loop.setHookEngine(hookEngine);
        return loop;
    }

    /** 供 WsHandler 使用的回复便捷方法（静态，避免循环依赖）。 */
    static PermissionReply toReply(String decision) {
        return switch (decision == null ? "" : decision) {
            case "ALLOW_ALWAYS" -> new PermissionReply(PermissionReply.Decision.ALLOW_ALWAYS, null);
            case "ALLOW_ONCE", "ALLOW" -> new PermissionReply(PermissionReply.Decision.ALLOW_ONCE, null);
            default -> new PermissionReply(PermissionReply.Decision.REJECT, "用户拒绝");
        };
    }
}
