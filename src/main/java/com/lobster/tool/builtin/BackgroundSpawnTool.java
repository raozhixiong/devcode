package com.lobster.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lobster.agent.AgentLoop;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.model.Part;
import com.lobster.store.MessageStore;
import com.lobster.store.TaskStore;
import com.lobster.tool.Tool;
import com.lobster.tool.ToolContext;
import com.lobster.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台子代理（对齐 FR-A6-3/A6-5）：非阻塞 spawn 隔离子会话，
 * 完成后 announce 合成 user 消息注入父会话并重新触发循环。
 * 提示模型"不要 sleep/轮询，完成会自动通知"。
 * 集成 TaskStore 写入任务台账（FR-C1-1）。
 */
public class BackgroundSpawnTool implements Tool {

    private final MessageStore store;
    private final EventBus bus;
    private final AgentLoop loop;
    private final TaskStore taskStore;
    /** 已派生后台任务（taskKey -> 完成标志），供查询。 */
    private final Map<String, String> running = new ConcurrentHashMap<>();

    public BackgroundSpawnTool(MessageStore store, EventBus bus, AgentLoop loop, TaskStore taskStore) {
        this.store = store;
        this.bus = bus;
        this.loop = loop;
        this.taskStore = taskStore;
    }

    @Override public String id() { return "background_spawn"; }

    @Override public String description() {
        return "Spawn a background sub-agent (non-blocking). It runs in an isolated session. "
                + "When finished, its result is announced into this session as a new message "
                + "and the loop resumes automatically. Do NOT sleep or poll.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "taskKey", Map.of("type", "string", "description", "Unique key to identify the task"),
                        "description", Map.of("type", "string", "description", "Short task description"),
                        "prompt", Map.of("type", "string", "description", "Full instructions for the background agent")),
                "required", List.of("taskKey", "description", "prompt"));
    }

    public boolean isRunning(String taskKey) { return running.containsKey(taskKey); }

    @Override public ToolResult execute(JsonNode args, ToolContext ctx) throws Exception {
        String taskKey = args.path("taskKey").asText();
        String description = args.path("description").asText("background task");
        String prompt = args.path("prompt").asText();
        if (taskKey.isEmpty() || running.containsKey(taskKey)) {
            return ToolResult.of("BackgroundSpawn", "Error: taskKey 已存在或为空: " + taskKey);
        }

        var child = store.createSession("bg-" + taskKey, "task", System.getProperty("user.dir"));
        store.appendUser(child.id(), List.of(new Part.Text(prompt, false, false)));
        running.put(taskKey, child.id());
        String ownerKey = "agent:main:" + ctx.sessionId();
        var task = taskStore.createSubagent(ownerKey, prompt, description,
                "main", child.id(), null, "main");
        bus.publish(new com.lobster.event.LobsterEvent(Events.TASK_STARTED, ctx.sessionId(),
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("taskKey", taskKey).put("taskId", task.id())
                        .put("description", description), true));

        Thread.ofVirtual().name("bg-agent-" + taskKey).start(() -> {
            taskStore.markRunning(task.id());
            String answer = "(no output)";
            try {
                loop.run(child.id());
                answer = store.loadActive(child.id()).stream()
                        .filter(m -> "assistant".equals(m.role()))
                        .flatMap(m -> m.parts().stream())
                        .filter(p -> p instanceof Part.Text)
                        .map(p -> ((Part.Text) p).text())
                        .reduce("", (a, b) -> b);
                if (answer.isEmpty()) answer = "(no output)";
                taskStore.markSucceeded(task.id(), answer);
            } catch (Exception e) {
                answer = "后台任务失败: " + e.getMessage();
                taskStore.markFailed(task.id(), e.getMessage());
            } finally {
                running.remove(taskKey);
            }
            // announce：合成 user 消息注入父会话
            String announce = "[后台任务完成] " + description + " (taskKey=" + taskKey + ")\n\n" + answer;
            store.appendUser(ctx.sessionId(), List.of(new Part.Text(announce, true, false)));
            bus.publish(new com.lobster.event.LobsterEvent(Events.TASK_ANNOUNCED, ctx.sessionId(),
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                            .put("taskKey", taskKey).put("taskId", task.id())
                            .put("text", announce), true));
            // 父会话空闲则重新触发循环；busy 则由收件箱/下一轮处理
            if (!loop.isBusy(ctx.sessionId())) {
                Thread.ofVirtual().name("agent-loop-" + ctx.sessionId()).start(() -> {
                    try { loop.run(ctx.sessionId()); } catch (Exception ignored) {}
                });
            }
        });
        return ToolResult.of("BackgroundSpawn: " + description,
                "后台任务已启动（taskKey=" + taskKey + ", taskId=" + task.id() + "）。完成时会自动注入结果并继续本会话，无需轮询。");
    }
}
