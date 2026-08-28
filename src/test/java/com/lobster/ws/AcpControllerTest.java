package com.lobster.ws;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"lobster.state-dir=target/test-state-acp"})
class AcpControllerTest {

    @Autowired TestRestTemplate rest;
    @Autowired Environment env;

    @Test
    void agentsListed() {
        int port = env.getProperty("local.server.port", Integer.class);
        ResponseEntity<Map> r = rest.getForEntity("http://localhost:" + port + "/acp/v1/agents", Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().containsKey("agents"));
    }

    @Test
    void createSessionRunsAgent() {
        int port = env.getProperty("local.server.port", Integer.class);
        ResponseEntity<Map> r = rest.postForEntity("http://localhost:" + port + "/acp/v1/sessions",
                Map.of("agent", "main", "message", "hello acp"), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().containsKey("sessionKey"));
    }
}
