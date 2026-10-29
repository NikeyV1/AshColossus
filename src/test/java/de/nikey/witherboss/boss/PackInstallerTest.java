package de.nikey.witherboss.boss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class PackInstallerTest {
    @TempDir Path dir;

    @Test
    void embeddedPackReplacesOldFileWithBackupAndAutoHash() throws Exception {
        Files.write(dir.resolve(PackInstaller.FILE), new byte[] {1, 2, 3});
        byte[] pack = {9, 8, 7, 6};
        String hash = PackInstaller.install(dir, pack, "1.0.0", "auto", null);
        assertEquals(PackInstaller.sha1(pack), hash);
        assertArrayEquals(pack, Files.readAllBytes(dir.resolve(PackInstaller.FILE)));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".bak")));
        }
        assertEquals(hash, PackInstaller.install(dir, pack, "1.0.0", "", null));
    }

    @Test
    void explicitHashIsRespectedAndMissingPackFailsLoudly() throws Exception {
        assertEquals("abcabcabcabcabcabcabcabcabcabcabcabcabca",
                PackInstaller.install(dir, new byte[] {1}, "1", "ABCABCABCABCABCABCABCABCABCABCABCABCABCA", null));
        Path empty = dir.resolve("empty");
        assertThrows(java.io.IOException.class, () -> PackInstaller.install(empty, null, "1", "auto", null));
    }
}
