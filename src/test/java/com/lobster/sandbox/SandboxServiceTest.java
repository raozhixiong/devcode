package com.lobster.sandbox;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SandboxServiceTest {

    private SandboxService with(Map<String, String> cfg) {
        return new SandboxService(cfg::get);
    }

    @Test
    void offByDefault() {
        var s = with(Map.of());
        assertEquals(SandboxService.Mode.OFF, s.mode());
        assertFalse(s.applies("main"));
    }

    @Test
    void nonMainSandboxesNonMainAgentOnly() {
        var s = with(Map.of("sandbox.mode", "non-main", "sandbox.backend", "local"));
        assertFalse(s.applies("main"));
        assertTrue(s.applies("agentA"));
    }

    @Test
    void allModeSandboxesEverything() {
        var s = with(Map.of("sandbox.mode", "all", "sandbox.backend", "local"));
        assertTrue(s.applies("main"));
        assertTrue(s.applies("agentX"));
    }

    @Test
    void localBackendExecutesCommand() throws Exception {
        var s = with(Map.of("sandbox.mode", "all", "sandbox.backend", "local", "sandbox.workspace", "."));
        Optional<String> out = s.tryExecute("main", "echo lobster-sandbox", 10_000);
        assertTrue(out.isPresent());
        assertTrue(out.get().contains("lobster-sandbox"));
    }

    @Test
    void notSandboxedReturnsEmpty() throws Exception {
        var s = with(Map.of("sandbox.mode", "off"));
        assertEquals(Optional.empty(), s.tryExecute("main", "echo x", 1000));
    }
}
