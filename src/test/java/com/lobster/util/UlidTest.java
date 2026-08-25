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
        assertEquals(30, a.length()); // 4 前缀 + 26 ULID
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
