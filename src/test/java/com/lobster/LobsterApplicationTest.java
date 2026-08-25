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
