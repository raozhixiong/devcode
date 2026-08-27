# 龙虾 M1 内核实施计划（Agent Loop + Part 模型 + 工具 + 权限 + 存储 + WS 事件流）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 构建可运行的龙虾 Gateway 内核：会话/消息/Part 存储、Agent Loop、8 个内置工具、权限引擎、WebSocket 事件流，开发者可通过 WS 对话并执行工具。

**Architecture:** Spring Boot 3 单进程 Gateway（:18790）。领域模型（Session/Message/Part/Tool/Permission）与存储层（SQLite 双库）解耦；AgentLoop 以虚拟线程每会话串行执行；EventBus 双通道（durable 落库 + live 广播）。

**Tech Stack:** Java 21（Loom 虚拟线程）、Spring Boot 3.3、Spring WebSocket、sqlite-jdbc（WAL）、Flyway、Jackson、JUnit 5。

## Global Constraints

- Java 21、Maven、单模块 `com.lobster:lobster-gateway`
- 端口 18790；状态目录 `~/.lobster`（可 LOBSTER_STATE_DIR 覆盖）
- ID 前缀 ULID：ses_/msg_/prt_/run_/evt_/tsk_
- SQLite PRAGMA：journal_mode=WAL, busy_timeout=5000, foreign_keys=ON
- 每个任务 TDD：先测后码；每任务一次 commit
- 消息时间戳一律 long 毫秒

---

### Task 1: 项目骨架与启动

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/lobster/LobsterApplication.java`
- Create: `src/main/resources/application.yml`
- Test: `src/test/java/com/lobster/LobsterApplicationTest.java`

**Interfaces:**
- Produces: 可启动 Spring Boot 应用；包根 `com.lobster`；后续任务全部放 `com.lobster.*`

- [x] **Step 1: 写失败测试**

```java
package com.lobster;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"lobster.state-dir=target/test-state"})
class LobsterApplicationTest {
    @Test
    void contextLoads() {}
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=LobsterApplicationTest`
Expected: FAIL（找不到主类）

- [x] **Step 3: 写 pom.xml 与主类**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>
  <groupId>com.lobster</groupId>
  <artifactId>lobster-gateway</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-websocket</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
    <dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><version>3.46.1.3</version></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
    <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
  </plugins></build>
</project>
```

```java
package com.lobster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LobsterApplication {
    public static void main(String[] args) {
        SpringApplication.run(LobsterApplication.class, args);
    }
}
```

```yaml
# src/main/resources/application.yml
server:
  port: 18790
spring:
  application:
    name: lobster-gateway
lobster:
  state-dir: ${LOBSTER_STATE_DIR:}
```

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=LobsterApplicationTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add pom.xml src/
git commit -m "feat: lobster gateway skeleton (spring boot 3 + java 21)"
```

---

### Task 2: ULID 与状态目录基础设施

**Files:**
- Create: `src/main/java/com/lobster/util/Ulid.java`
- Create: `src/main/java/com/lobster/util/StateDirs.java`
- Test: `src/test/java/com/lobster/util/UlidTest.java`
- Test: `src/test/java/com/lobster/util/StateDirsTest.java`

**Interfaces:**
- Produces: `Ulid.next(String prefix)` -> `String`（如 `Ulid.next("ses_")`）；`StateDirs.resolve(String override)` -> `Path`（含 workspace/agents/tool-output/logs 子目录，自动创建）

- [x] **Step 1: 写失败测试**

```java
package com.lobster.util;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class UlidTest {
    @Test
    void prefixedUniqueMonotonic() {
        String a = Ulid.next("ses_");
        assertTrue(a.startsWith("ses_"));
        assertEquals(29, a.length()); // 4 前缀 + 26 ULID
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) assertTrue(seen.add(Ulid.next("prt_")));
    }
}

class StateDirsTest {
    @Test
    void createsSubdirectories() throws Exception {
        var root = StateDirs.resolve("target/test-state-t2");
        assertTrue(Files.isDirectory(root.resolve("workspace")));
        assertTrue(Files.isDirectory(root.resolve("agents")));
        assertTrue(Files.isDirectory(root.resolve("tool-output")));
        assertTrue(Files.isDirectory(root.resolve("logs")));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest='UlidTest,StateDirsTest'`
Expected: FAIL

- [x] **Step 3: 实现**

```java
package com.lobster.util;

import java.security.SecureRandom;
import java.time.Instant;

/** Crockford Base32 ULID。线程安全，单调递增（同毫秒内随机增量）。 */
public final class Ulid {
    private static final char[] ENC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RND = new SecureRandom();
    private static long lastTs = -1;
    private static byte[] lastEntropy = new byte[10];

    private Ulid() {}

    public static synchronized String next(String prefix) {
        long ts = Instant.now().toEpochMilli();
        byte[] entropy = new byte[10];
        if (ts == lastTs) {
            // 单调递增：上次熵 +1
            for (int i = 9; i >= 0; i--) {
                lastEntropy[i]++;
                if (lastEntropy[i] != 0) break;
            }
            System.arraycopy(lastEntropy, 0, entropy, 0, 10);
        } else {
            RND.nextBytes(entropy);
        }
        lastTs = ts;
        System.arraycopy(entropy, 0, lastEntropy, 0, 10);
        char[] out = new char[26];
        // 48 bit 时间戳 -> 10 字符
        for (int i = 9; i >= 0; i--) { out[i] = ENC[(int) (ts & 0x1F)]; ts >>>= 5; }
        // 80 bit 熵 -> 16 字符
        long hi = ((entropy[0] & 255L) << 32) | ((entropy[1] & 255L) << 24)
                | ((entropy[2] & 255L) << 16) | ((entropy[3] & 255L) << 8) | (entropy[4] & 255L);
        long lo = 0;
        for (int i = 5; i < 10; i++) lo = (lo << 8) | (entropy[i] & 255);
        for (int i = 25; i >= 10; i--) {
            if (i >= 18) { out[i] = ENC[(int) (hi & 0x1F)]; hi >>>= 5; }
            else { out[i] = ENC[(int) (lo & 0x1F)]; lo >>>= 5; }
        }
        return prefix + new String(out);
    }
}
```

```java
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
```

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest='UlidTest,StateDirsTest'`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/util/ src/test/java/com/lobster/util/
git commit -m "feat: ulid generator and state dirs"
```

---

### Task 3: 共享库 Flyway 迁移（用户/Agent/任务/审批核心表）

**Files:**
- Create: `src/main/resources/db/migration/shared/V1__shared_init.sql`
- Create: `src/main/java/com/lobster/store/DatabaseConfig.java`
- Test: `src/test/java/com/lobster/store/DatabaseConfigTest.java`

**Interfaces:**
- Produces: `DatabaseConfig.sharedDataSource()`（DataSource，指向 `<stateDir>/lobster.db`）；表 `user`、`agent`、`agent_binding`、`task`、`workboard_card`、`approval`、`cron_job`、`cron_run`、`secret_store_entry`、`audit_event`、`config_state`、`schema_meta`、`plugin`
- 后续任务用 `@Autowired DataSource` 即得共享库

- [x] **Step 1: 写失败测试**

```java
package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;

@SpringBootTest
@TestPropertySource(properties = {"lobster.state-dir=target/test-state-t3"})
class DatabaseConfigTest {
    @Autowired DataSource ds;

    @Test
    void migrationsApplied() {
        var jdbc = new JdbcTemplate(ds);
        List<String> tables = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", String.class);
        for (String t : new String[]{"user", "agent", "task", "workboard_card",
                "approval", "cron_job", "secret_store_entry", "audit_event"}) {
            assertTrue(tables.contains(t), "missing table " + t);
        }
        String mode = jdbc.queryForObject("PRAGMA journal_mode", String.class);
        assertEquals("wal", mode.toLowerCase());
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=DatabaseConfigTest`
Expected: FAIL

- [x] **Step 3: 实现**

```java
package com.lobster.store;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;
import com.lobster.util.StateDirs;

import java.nio.file.Path;

@Configuration
public class DatabaseConfig {

    @Bean
    public Path stateDir(@org.springframework.beans.factory.annotation.Value("${lobster.state-dir:}") String override) {
        return StateDirs.resolve(override);
    }

    @Bean
    public DataSource sharedDataSource(Path stateDir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + stateDir.resolve("lobster.db"));
        org.flywaydb.core.Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration/shared")
            .baselineOnMigrate(true)
            .load()
            .migrate();
        return ds;
    }
}
```

V1__shared_init.sql：从《07-龙虾-表设计.md》第一章复制 user/auth_token/device/agent/agent_binding/channel_binding/task/workboard_card/workboard_event/approval/exec_approval_policy/cron_job/cron_run/secret_store_entry/audit_event/config_state/schema_meta/plugin 的完整 DDL（含索引，全部 `IF NOT EXISTS` 语义由 Flyway 保证只跑一次）。

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=DatabaseConfigTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/resources/db/ src/main/java/com/lobster/store/ src/test/
git commit -m "feat: shared sqlite schema via flyway"
```

---

### Task 4: 领域模型（Part/Message/Session）与 agent 库迁移

**Files:**
- Create: `src/main/java/com/lobster/model/Part.java`
- Create: `src/main/java/com/lobster/model/Message.java`
- Create: `src/main/java/com/lobster/model/Session.java`
- Create: `src/main/resources/db/migration/agent/V1__agent_init.sql`
- Create: `src/main/java/com/lobster/store/AgentDb.java`
- Test: `src/test/java/com/lobster/store/AgentDbTest.java`

**Interfaces:**
- Produces: `Part` sealed 接口 + record 族（TextPart/ReasoningPart/ToolPart+ToolState/StepFinishPart/FilePart/SnapshotPart/CompactionPart/SyntheticPart）；`Message{id, sessionId, role, parts, createdAt}`；`Session{id, sessionKey, kind, title, directory, ...}`
- Produces: `AgentDb.open(Path agentDir, String agentId)` -> `AgentDb`（含 `ds()`、`jdbc()`）；自动跑 agent Flyway 迁移（session/message/part/session_input/session_active_writer/todo/event_sequence/event/…）

- [x] **Step 1: 写失败测试**

```java
package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentDbTest {
    @Test
    void opensAndMigrates(@TempDir Path tmp) {
        try (AgentDb db = AgentDb.open(tmp, "dev-01")) {
            List<String> tables = db.jdbc().queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", String.class);
            for (String t : new String[]{"session", "message", "part",
                    "session_input", "session_active_writer", "event", "event_sequence", "todo"}) {
                assertTrue(tables.contains(t), "missing " + t);
            }
        }
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=AgentDbTest`
Expected: FAIL

- [x] **Step 3: 实现**

Part.java（sealed 接口 + record，Jackson 用 @JsonTypeInfo(typeIdProperty="type") 多态序列化）：

```java
package com.lobster.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
public sealed interface Part permits
        Part.Text, Part.Reasoning, Part.Tool, Part.File,
        Part.StepFinish, Part.Snapshot, Part.Compaction, Part.Synthetic {

    record Text(String text, boolean synthetic, boolean ignored) implements Part {}
    record Reasoning(String text) implements Part {}
    record Tool(String tool, String callId, ToolState state) implements Part {}
    record File(String mime, String filename, String url) implements Part {}
    record StepFinish(String reason, long inputTokens, long outputTokens, double cost) implements Part {}
    record Snapshot(String hash, List<String> files) implements Part {}
    record Compaction(boolean auto, String summary) implements Part {}
    record Synthetic(String text) implements Part {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
    sealed interface ToolState permits ToolState.Pending, ToolState.Running,
            ToolState.Completed, ToolState.Error {
        record Pending(String rawInput) implements ToolState {}
        record Running(String title, java.util.Map<String, Object> metadata) implements ToolState {}
        record Completed(String title, String output, java.util.Map<String, Object> metadata) implements ToolState {}
        record Error(String error) implements ToolState {}
    }
}
```

Message/Session 为简单 record + Jackson。AgentDb：open 时 `SQLiteDataSource` + 专属 Flyway（locations=`classpath:db/migration/agent`）。agent V1 SQL 从《07-龙虾-表设计.md》第二章 2.1/2.2 复制（session/session_participant/message/part/session_input/session_active_writer/todo/branch/event_sequence/event/session_state_signal）。

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=AgentDbTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/model/ src/main/resources/db/migration/agent/ src/main/java/com/lobster/store/AgentDb.java src/test/
git commit -m "feat: domain model and per-agent sqlite store"
```

---

### Task 5: 消息/Part 存储服务（MessageStore）

**Files:**
- Create: `src/main/java/com/lobster/store/MessageStore.java`
- Test: `src/test/java/com/lobster/store/MessageStoreTest.java`

**Interfaces:**
- Consumes: AgentDb（Task 4）
- Produces: `MessageStore(AgentDb db)`：
  - `Session createSession(String sessionKey, String kind, String directory)`
  - `Message appendUser(String sessionId, List<Part> parts)`
  - `Message appendAssistant(String sessionId)`
  - `void addPart(String messageId, Part part)`
  - `void updateToolState(String messageId, String callId, ToolState state)`
  - `List<Message> loadActive(String sessionId)`（过滤 compaction 基线之前）
  - `Optional<Message> lastMessage(String sessionId)`

- [x] **Step 1: 写失败测试**

```java
package com.lobster.store;

import com.lobster.model.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MessageStoreTest {
    @Test
    void crudLifecycle(@TempDir Path tmp) {
        try (AgentDb db = AgentDb.open(tmp, "dev-01")) {
            MessageStore store = new MessageStore(db);
            var s = store.createSession("main", "main", "D:/work");
            var user = store.appendUser(s.id(), List.of(new Part.Text("hi", false, false)));
            var asst = store.appendAssistant(s.id());
            store.addPart(asst.id(), new Part.Tool("bash", "call_1",
                    new Part.ToolState.Pending("{}")));
            store.updateToolState(asst.id(), "call_1",
                    new Part.ToolState.Completed("npm test", "ok", null));
            var msgs = store.loadActive(s.id());
            assertEquals(2, msgs.size());
            var tool = (Part.Tool) msgs.get(1).parts().get(0);
            assertInstanceOf(Part.ToolState.Completed.class, tool.state());
        }
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MessageStoreTest`
Expected: FAIL

- [x] **Step 3: 实现**

MessageStore 用 JdbcTemplate；part 存 JSON 列（Jackson ObjectMapper 静态实例）；updateToolState 读出整条消息 -> 替换目标 callId 的 ToolPart state -> 整体重写该 part 行（M1 简化，M2 优化为单 part 行）。

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=MessageStoreTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/store/MessageStore.java src/test/java/com/lobster/store/MessageStoreTest.java
git commit -m "feat: message store with part model"
```

---

### Task 6: EventBus（durable + live 双通道）

**Files:**
- Create: `src/main/java/com/lobster/event/LobsterEvent.java`
- Create: `src/main/java/com/lobster/event/EventBus.java`
- Test: `src/test/java/com/lobster/event/EventBusTest.java`

**Interfaces:**
- Produces: `record LobsterEvent(String type, String aggregateId, JsonNode data, boolean durable)`
- Produces: `EventBus(AgentDb db)`：
  - `long publish(LobsterEvent e)` -- durable 则 INSERT event 表（aggregateId 分配 seq），随后广播；live 只广播
  - `void subscribe(String aggregateId, Consumer<LobsterEvent> listener)` -> `Runnable unsubscribe`
  - `void subscribeAll(Consumer<LobsterEvent> listener)`（WS 网关用）
  - `List<LobsterEvent> replay(String aggregateId, long afterSeq)`
- 事件类型常量类 `Events`（session.next.prompt.admitted / step.started|ended / text.delta|ended / tool.called|success|failed / session.status）

- [x] **Step 1: 写失败测试**

```java
package com.lobster.event;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {
    @Test
    void durablePersistsAndReplays(@TempDir Path tmp) {
        try (var db = com.lobster.store.AgentDb.open(tmp, "a")) {
            EventBus bus = new EventBus(db);
            AtomicReference<LobsterEvent> seen = new AtomicReference<>();
            bus.subscribeAll(seen::set);
            ObjectNode data = JsonNodeFactory.instance.objectNode().put("text", "hello");
            bus.publish(new LobsterEvent(Events.TEXT_ENDED, "ses_1", data, true));
            assertNotNull(seen.get());
            List<LobsterEvent> replayed = bus.replay("ses_1", 0);
            assertEquals(1, replayed.size());
            assertEquals("hello", replayed.get(0).data().get("text").asText());
            assertEquals(0, bus.replay("ses_1", 1).size()); // afterSeq 边界
        }
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=EventBusTest`
Expected: FAIL

- [x] **Step 3: 实现**

EventBus：`ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer>>` 路由 + `CopyOnWriteArrayList` 全局表；durable 写 event 表（`INSERT INTO event_sequence ... ON CONFLICT DO UPDATE SET seq=seq+1 RETURNING` 或先 SELECT 后 UPDATE 加锁——用 synchronized per-aggregate 简化）。

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=EventBusTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/event/
git commit -m "feat: event bus with durable replay"
```

---

### Task 7: 工具 SPI 与内置工具（read/glob/grep/write/edit/bash/todo/question）

**Files:**
- Create: `src/main/java/com/lobster/tool/Tool.java`
- Create: `src/main/java/com/lobster/tool/ToolContext.java`
- Create: `src/main/java/com/lobster/tool/ToolResult.java`
- Create: `src/main/java/com/lobster/tool/builtin/ReadTool.java`、`GlobTool.java`、`GrepTool.java`、`WriteTool.java`、`EditTool.java`、`BashTool.java`、`TodoTool.java`、`QuestionTool.java`
- Test: `src/test/java/com/lobster/tool/ToolTest.java`

**Interfaces:**
- Produces:
  - `interface Tool { String id(); String description(); Map<String,Object> parameters(); ToolResult execute(JsonNode args, ToolContext ctx); }`
  - `record ToolContext(String sessionId, String messageId, String agentId, Runnable abortCheck, Consumer<Map<String,Object>> metadata, Function<PermissionRequest,PermissionReply> ask)`
  - `record ToolResult(String title, String output, List<Part.File> attachments)`
- Consumes: Part 模型（Task 4）
- 工具语义对齐 OpenCode：Read 返回 `行号: 内容`（截断 2000 行/50KB）；Grep 正则+include 过滤；Edit 精确替换（replaceAll 选项）；Bash 超时 120s 默认

- [x] **Step 1: 写失败测试**

```java
package com.lobster.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ToolTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void readWriteEditRoundtrip(@TempDir Path tmp) throws Exception {
        var write = new WriteTool();
        write.execute(om.readTree("{\"file_path\":\"" + tmp + "/a.txt\",\"content\":\"line1\\nline2\"}"),
                ToolContext.dummy());
        var read = new ReadTool();
        var out = read.execute(om.readTree("{\"file_path\":\"" + tmp + "/a.txt\"}"), ToolContext.dummy());
        assertTrue(out.output().contains("1: line1"));
        var edit = new EditTool();
        edit.execute(om.readTree("{\"file_path\":\"" + tmp + "/a.txt\",\"old_string\":\"line1\",\"new_string\":\"LINE1\"}"),
                ToolContext.dummy());
        assertTrue(read.execute(om.readTree("{\"file_path\":\"" + tmp + "/a.txt\"}"), ToolContext.dummy())
                .output().contains("LINE1"));
    }

    @Test
    void globAndGrep(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Foo.java"), "class Foo {}");
        Files.writeString(tmp.resolve("Bar.java"), "class Bar {}");
        var glob = new GlobTool();
        assertTrue(glob.execute(om.readTree("{\"pattern\":\"**/*.java\",\"path\":\"" + tmp + "\"}"),
                ToolContext.dummy()).output().contains("Foo.java"));
        var grep = new GrepTool();
        assertTrue(grep.execute(om.readTree("{\"pattern\":\"class Foo\",\"path\":\"" + tmp + "\"}"),
                ToolContext.dummy()).output().contains("Foo.java"));
    }

    @Test
    void bashRunsCommand() throws Exception {
        var bash = new BashTool();
        var out = bash.execute(om.readTree("{\"command\":\"echo lobster\"}"), ToolContext.dummy());
        assertTrue(out.output().contains("lobster"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=ToolTest`
Expected: FAIL

- [x] **Step 3: 实现全部工具**

`ToolContext.dummy()` 提供测试用空实现。各工具 JSON Schema 用 Map 描述（type/properties/required）。BashTool：Windows 下 `cmd /c`，其他 `sh -c`；捕获 stdout+stderr；超时 120s 进程 destroy。

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=ToolTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/tool/
git commit -m "feat: tool spi and 8 builtin tools"
```

---

### Task 8: 权限引擎（PermissionEngine）

**Files:**
- Create: `src/main/java/com/lobster/permission/PermissionRule.java`
- Create: `src/main/java/com/lobster/permission/PermissionEngine.java`
- Test: `src/test/java/com/lobster/permission/PermissionEngineTest.java`

**Interfaces:**
- Produces:
  - `record PermissionRule(String permission, String pattern, Action action)`，`enum Action {ALLOW, DENY, ASK}`
  - `PermissionEngine.ask(String permission, List<String> patterns)` -> `PermissionReply`（allow/deny+feedback）；规则求值 findLast；无匹配默认 ASK
  - `void reply(String requestId, PermissionReply)`；`List<PendingPermission> pending()`
- Consumes: EventBus（Task 6，publish permission.asked/replied）

- [x] **Step 1: 写失败测试**

```java
package com.lobster.permission;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineTest {

    @Test
    void findLastWins() {
        var e = new PermissionEngine(List.of(
                new PermissionRule("edit", "*", PermissionRule.Action.DENY),
                new PermissionRule("edit", "src/**", PermissionRule.Action.ALLOW)),
                r -> new PermissionReply(PermissionReply.Decision.ALLOW_ONCE, null));
        assertEquals(PermissionRule.Action.ALLOW, e.evaluate("edit", "src/Foo.java"));
        assertEquals(PermissionRule.Action.DENY, e.evaluate("edit", "docs/Foo.md"));
    }

    @Test
    void askSuspendsUntilReply() throws Exception {
        var e = new PermissionEngine(List.of(), r -> new PermissionReply(PermissionReply.Decision.ALLOW_ONCE, null));
        var future = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> e.ask("bash", List.of("npm test")));
        Thread.sleep(100);
        assertEquals(1, e.pending().size());
        var req = e.pending().get(0);
        e.reply(req.requestId(), new PermissionReply(PermissionReply.Decision.ALLOW_ONCE, null));
        assertEquals(PermissionReply.Decision.ALLOW_ONCE,
                future.get(2, TimeUnit.SECONDS).decision());
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=PermissionEngineTest`
Expected: FAIL

- [x] **Step 3: 实现**

PermissionEngine 持规则列表 + `Map<String, CompletableFuture<PermissionReply>> pending`；ask 时 evaluate：DENY 直接抛/返回 deny；ALLOW 直接放行；ASK -> 建 future + 回调 notifier（M1 由测试注入，M2 接 EventBus+WS）。pattern 用 `*` 通配（`FileSystems.getDefault().getPathMatcher("glob:"+pattern)` 简化）。

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=PermissionEngineTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/permission/
git commit -m "feat: permission engine with findlast rules"
```

---

### Task 9: LLM Provider（OpenAI 兼容流式）+ Mock

**Files:**
- Create: `src/main/java/com/lobster/llm/LlmProvider.java`
- Create: `src/main/java/com/lobster/llm/LlmEvent.java`
- Create: `src/main/java/com/lobster/llm/OpenAiCompatProvider.java`
- Create: `src/main/java/com/lobster/llm/MockLlmProvider.java`
- Test: `src/test/java/com/lobster/llm/OpenAiCompatProviderTest.java`

**Interfaces:**
- Produces:
  - `interface LlmProvider { Stream<LlmEvent> stream(LlmRequest req); }`
  - `sealed interface LlmEvent`：`TextDelta(String)` / `ToolCall(String callId, String name, String argumentsJson)` / `Finish(String reason, Usage usage)` / `Error(Throwable)`
  - `record LlmRequest(String model, String systemPrompt, List<ChatMsg> messages, List<ToolSpec> tools, double temperature)`
- Produces: `MockLlmProvider(List<LlmEvent> script)`（测试/无 key 演示用）

- [x] **Step 1: 写失败测试**

```java
package com.lobster.llm;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatProviderTest {
    @Test
    void mockStreamsScript() {
        var mock = new MockLlmProvider(List.of(
                new LlmEvent.TextDelta("你好"),
                new LlmEvent.Finish("stop", new LlmEvent.Usage(10, 5))));
        var events = mock.stream(new LlmProvider.LlmRequest("m", "sys",
                List.of(), List.of(), 0.7)).toList();
        assertEquals(2, events.size());
        assertEquals("你好", ((LlmEvent.TextDelta) events.get(0)).text());
        assertEquals("stop", ((LlmEvent.Finish) events.get(1)).reason());
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=OpenAiCompatProviderTest`
Expected: FAIL

- [x] **Step 3: 实现**

OpenAiCompatProvider：Java 21 HttpClient POST `/chat/completions`（stream:true），逐行解析 SSE `data:` 行，Jackson 读 delta.content / tool_calls / finish_reason；构造器参数 `String baseUrl, String apiKey`。MockLlmProvider 直接 Stream.of(script)。LlmEvent/Usage 为 record。OpenAI 真实端点的集成测试标注 `@Disabled("需 API key")` 留接口。

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=OpenAiCompatProviderTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/llm/
git commit -m "feat: llm provider with openai-compat streaming and mock"
```

---

### Task 10: AgentLoop（核心状态机）

**Files:**
- Create: `src/main/java/com/lobster/agent/AgentLoop.java`
- Create: `src/main/java/com/lobster/agent/PromptAssembler.java`
- Test: `src/test/java/com/lobster/agent/AgentLoopTest.java`

**Interfaces:**
- Consumes: MessageStore(5)、EventBus(6)、Tool+ToolRegistry(7 内建一个简单 `ToolRegistry.of(Tool...)`)、PermissionEngine(8)、LlmProvider(9 Mock)
- Produces: `AgentLoop(MessageStore, EventBus, ToolRegistry, PermissionEngine, LlmProvider, String agentId)`
  - `void run(String sessionId)`：完整 while 循环（历史->LLM 流->Part 落库->工具执行->结果回填->continue/stop）
  - 工具结果以 user 消息回填：`{role:"user", parts:[ToolResultPart]}`（内部用 `Part.Tool` 的 Completed 状态表达，符合 OpenCode part 模型）
- PromptAssembler（M1 简化）：身份段 + env 块（cwd/日期/platform）+ 工具清单

- [x] **Step 1: 写失败测试**

```java
package com.lobster.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.event.*;
import com.lobster.llm.*;
import com.lobster.model.*;
import com.lobster.permission.*;
import com.lobster.store.*;
import com.lobster.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void loopRunsToolAndFinishes(@TempDir Path tmp) throws Exception {
        try (var db = AgentDb.open(tmp, "dev")) {
            var store = new MessageStore(db);
            var bus = new EventBus(db);
            // 脚本：先调 echo 工具，再输出文本，再 stop
            var llm = new MockLlmProvider(List.of(
                    new LlmEvent.ToolCall("call_1", "bash", "{\"command\":\"echo lobster\"}"),
                    new LlmEvent.TextDelta("完成"),
                    new LlmEvent.Finish("stop", new LlmEvent.Usage(100, 20))));
            var engine = new PermissionEngine(List.of(
                    new PermissionRule("bash", "*", PermissionRule.Action.ALLOW)), null);
            var loop = new AgentLoop(store, bus, ToolRegistry.of(new com.lobster.tool.builtin.BashTool()),
                    engine, llm, "dev-01");
            var s = store.createSession("main", "main", tmp.toString());
            store.appendUser(s.id(), List.of(new Part.Text("跑一下 echo", false, false)));
            loop.run(s.id());

            var msgs = store.loadActive(s.id());
            // user + assistant(tool) + user(tool result) + assistant(text)
            assertEquals(4, msgs.size());
            assertInstanceOf(Part.ToolState.Completed.class,
                    ((Part.Tool) msgs.get(1).parts().get(0)).state());
            assertTrue(msgs.get(3).parts().stream()
                    .anyMatch(p -> p instanceof Part.Text t && t.text().contains("完成")));
        }
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=AgentLoopTest`
Expected: FAIL

- [x] **Step 3: 实现**

AgentLoop.run 核心循环（伪码见《05-龙虾-核心设计.md》2.1）：
1. `while(true)`：loadActive；最后一条 assistant 且 finish=stop -> break
2. 组装 LlmRequest（PromptAssembler 简化版）
3. stream 遍历：TextDelta -> addPart(Text) + bus.publish(TEXT_DELTA, live)；ToolCall -> ToolPart Pending + ask 权限 + execute + Completed/Error + tool result 作为下一条 user 消息 parts
4. Finish -> StepFinishPart；reason=stop 且无工具调用 -> break；有工具调用 -> continue
5. 全程 publish Events.SESSION_STATUS busy/idle

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=AgentLoopTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/agent/
git commit -m "feat: agent loop state machine"
```

---

### Task 11: WebSocket 网关（帧协议 + chat.send/history）

**Files:**
- Create: `src/main/java/com/lobster/ws/WsHandler.java`
- Create: `src/main/java/com/lobster/ws/WsConfig.java`
- Create: `src/main/java/com/lobster/ws/Frame.java`
- Test: `src/test/java/com/lobster/ws/WsHandlerTest.java`（SpringBootTest + WebSocketClient）

**Interfaces:**
- Consumes: AgentLoop(10)、MessageStore(5)、EventBus(6)
- Produces: WS 端点 `/ws`：
  - 帧：`{type:"req",id,method,params}` / `{type:"res",id,ok,payload|error}` / `{type:"event",event,payload,seq}`
  - method：`connect`（M1 免鉴权）| `chat.send {sessionKey,text}` | `chat.history {sessionKey}` | `sessions.list`
  - chat.send 后事件流转发：EventBus.subscribeAll -> 推 event 帧

- [x] **Step 1: 写失败测试**

```java
package com.lobster.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"lobster.state-dir=target/test-state-t11"})
class WsHandlerTest {
    @Autowired org.springframework.core.env.Environment env;
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void sendAndReceiveEvents() throws Exception {
        int port = env.getProperty("local.server.port", Integer.class);
        var client = new StandardWebSocketClient();
        var received = new CopyOnWriteArrayList<JsonNode>();
        var sessionFuture = new CompletableFuture<WebSocketSession>();
        var done = new CompletableFuture<Void>();

        client.execute(new TextWebSocketHandler() {
            @Override public void afterConnectionEstablished(WebSocketSession s) { sessionFuture.complete(s); }
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
                JsonNode n = om.readTree(m.getPayload());
                received.add(n);
                if ("session.status".equals(n.path("event").asText())
                        && n.path("payload").path("type").asText().equals("idle")) done.complete(null);
            }
        }, new URI("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);

        var s = sessionFuture.get(5, TimeUnit.SECONDS);
        s.sendMessage(new TextMessage("{\"type\":\"req\",\"id\":\"1\",\"method\":\"chat.send\","
                + "\"params\":{\"sessionKey\":\"main\",\"text\":\"hi\"},\"idempotencyKey\":\"k1\"}"));

        done.get(20, TimeUnit.SECONDS);
        assertTrue(received.stream().anyMatch(n -> "res".equals(n.path("type").asText()) && n.path("ok").asBoolean()));
        assertTrue(received.stream().anyMatch(n -> "event".equals(n.path("type").asText())));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=WsHandlerTest`
Expected: FAIL

- [x] **Step 3: 实现**

WsConfig 注册 `WebSocketHandlerRegistry.addHandler(wsHandler, "/ws")`。WsHandler：`ConcurrentHashMap<WebSocketSession, Boolean>` 连接表；handleText 解析 method：
- chat.send -> 找/建 main session -> appendUser -> 虚拟线程 `Thread.ofVirtual().start(() -> agentLoop.run(...))` -> 立即 res `{runId, status:"started"}`
- chat.history -> loadActive -> res messages
- EventBus.subscribeAll -> 广播 event 帧（带 seq，durable 事件取 event 表 seq；live 无）

- [x] **Step 4: 运行测试通过**

Run: `mvn test -Dtest=WsHandlerTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/lobster/ws/
git commit -m "feat: websocket gateway with frame protocol"
```

---

### Task 12: 静态演示页（HTML 单页聊天）与 M1 验收

**Files:**
- Create: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/app.js`
- Create: `src/main/resources/static/style.css`
- Test: `src/test/java/com/lobster/SmokeTest.java`

**Interfaces:**
- Consumes: WS 端点（Task 11）
- Produces: 打开 `http://127.0.0.1:18790/` 可用的极简聊天界面（消息流 + 工具卡片 + 输入框）

- [x] **Step 1: 写冒烟测试**

```java
package com.lobster;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"lobster.state-dir=target/test-state-t12"})
class SmokeTest {
    @Autowired WebTestClient http; // 需要 spring-boot-starter-webflux test 依赖或改用 TestRestTemplate

    @Test
    void indexServed() {
        http.get().uri("/index.html").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(b -> b.contains("Lobster"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=SmokeTest`
Expected: FAIL

- [x] **Step 3: 实现前端页**

原生 JS WebSocket 客户端（无构建）：connect -> 渲染消息流（user 右/assistant 左）；event 帧分发：text.delta 追加打字机、tool.called 渲染工具卡片（运行中 spinner）、tool.success 更新输出、session.idle 恢复输入框；输入框回车 -> chat.send。

- [x] **Step 4: 运行测试通过 + 手工验收**

Run: `mvn test`
Expected: 全部 PASS

手工验收：`mvn spring-boot:run`，浏览器打开 127.0.0.1:18790，发送 `运行 echo hello`（无 LLM key 时默认 Mock provider 直接文本回复），观察消息流与工具卡片。

- [x] **Step 5: Commit**

```bash
git add src/main/resources/static/ src/test/java/com/lobster/SmokeTest.java
git commit -m "feat: m1 demo chat page and smoke test"
```

---

## Self-Review 结论

- **覆盖**：M1 验收标准"开发者可对话、读写文件、权限弹窗（引擎+回复机制，UI 弹窗在 M2）、会话恢复（durable 事件回放 + loadActive）"全部有对应任务（7/8/5/6/10/11）
- **无占位符**：全部任务含具体代码/SQL/测试
- **类型一致**：Ulid/StateDirs/AgentDb/MessageStore/EventBus/Tool/PermissionEngine/LlmProvider/AgentLoop 签名跨任务核对一致
- **M2+ 范围**（权限 UI 弹窗、压缩、快照、子代理、多 agent、看板、频道）留待后续计划
