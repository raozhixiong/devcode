package com.lobster.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

    @Test
    void builtinCommandsRegistered() {
        var r = CommandRegistry.builtin();
        assertEquals(3, r.list().size());
        assertNotNull(r.get("session.clear"));
        assertEquals("/clear", r.bySlash("/clear").slashName());
        assertEquals("/clear", r.bySlash("clear").slashName());
    }

    @Test
    void registerAndOverride() {
        var r = CommandRegistry.builtin();
        r.register(new CommandRegistry.Command("help", "帮助(重写)", "general", "/help", "x", "builtin"));
        assertEquals("帮助(重写)", r.get("help").title());
    }
}
