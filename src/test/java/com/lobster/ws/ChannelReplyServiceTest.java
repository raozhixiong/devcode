package com.lobster.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobster.event.EventBus;
import com.lobster.event.Events;
import com.lobster.event.LobsterEvent;
import com.lobster.model.Part;
import com.lobster.store.AgentDb;
import com.lobster.store.ChannelStore;
import com.lobster.store.MessageStore;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ChannelReplyServiceTest {

    @Test
    void postsReplyToOutbound(@TempDir java.nio.file.Path dir) throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("lobster.db"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/shared")
                .baselineOnMigrate(true).load().migrate();
        try (AgentDb adb = AgentDb.open(dir.resolve("agents"), "test")) {
            var ms = new MessageStore(adb);
            var chs = new ChannelStore(new JdbcTemplate(ds), new EventBus(adb));
            var bus = new EventBus(adb);

            AtomicReference<String> received = new AtomicReference<>();
            AtomicBoolean got = new AtomicBoolean(false);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                byte[] b = ex.getRequestBody().readAllBytes();
                received.set(new String(b, StandardCharsets.UTF_8));
                got.set(true);
                ex.sendResponseHeaders(200, -1);
            });
            server.start();
            int port = server.getAddress().getPort();

            var sess = ms.createSession("channel:feishu:acc1:u1", "main", System.getProperty("user.dir"));
            var assistant = ms.appendAssistant(sess.id());
            ms.addPart(assistant.id(), new Part.Text("你好，已处理完成", false, false));
            chs.create("feishu", "acc1", "main", "{\"outboundUrl\":\"http://127.0.0.1:" + port + "\"}");

            new ChannelReplyService(chs, ms, bus);
            bus.publish(new LobsterEvent(Events.SESSION_IDLE, sess.id(),
                    new ObjectMapper().createObjectNode(), false, 0));

            assertTrue(got.get());
            assertTrue(received.get().contains("已处理完成"));
            server.stop(0);
        }
    }
}
