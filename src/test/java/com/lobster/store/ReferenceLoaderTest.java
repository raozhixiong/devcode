package com.lobster.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceLoaderTest {

    @Test
    void loadsLocalFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("doc.md");
        Files.writeString(f, "# hello reference");
        var loader = new ReferenceLoader(p -> null);
        String content = loader.load(new ReferenceStore.Reference("r1", "doc", "local", f.toString(), "d", true), dir);
        assertTrue(content.contains("hello reference"));
    }

    @Test
    void unknownKindReportsError(@TempDir Path dir) {
        var loader = new ReferenceLoader(p -> null);
        String c = loader.load(new ReferenceStore.Reference("r1", "x", "ftp", "ftp://x", "d", true), dir);
        assertTrue(c.startsWith("ERROR:"));
    }
}
