package de.nikey.witherboss.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class AtomicFilesTest {
    @TempDir Path dir;

    private Path original() throws IOException {
        Path p = dir.resolve("data.yml");
        Files.writeString(p, "old");
        return p;
    }

    private Path pending() throws IOException {
        Path p = dir.resolve("data.yml.tmp");
        Files.writeString(p, "new");
        return p;
    }

    @Test
    void temporaryLockRetriesWithoutDeletingOriginal() throws Exception {
        Path old = original(), next = pending();
        AtomicInteger calls = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        AtomicFiles.replace(
                next,
                old,
                (a, b, options) -> {
                    if (calls.incrementAndGet() < 3) {
                        assertEquals("old", Files.readString(b));
                        throw new AccessDeniedException(b.toString());
                    }
                    Files.move(a, b, options);
                },
                delays::add);
        assertEquals("new", Files.readString(old));
        assertFalse(Files.exists(next));
        assertEquals(List.of(10L, 20L), delays);
    }

    @Test
    void permanentDenialFailsAndKeepsBothCopies() throws Exception {
        Path old = original(), next = pending();
        AtomicInteger calls = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        assertThrows(
                AccessDeniedException.class,
                () ->
                        AtomicFiles.replace(
                                next,
                                old,
                                (a, b, options) -> {
                                    calls.incrementAndGet();
                                    throw new AccessDeniedException(b.toString());
                                },
                                delays::add));
        assertEquals(5, calls.get());
        assertEquals(100, delays.stream().mapToLong(Long::longValue).sum());
        assertEquals("old", Files.readString(old));
        assertEquals("new", Files.readString(next));
    }

    @Test
    void unsupportedAtomicMoveUsesReplacingFallback() throws Exception {
        Path old = original(), next = pending();
        AtomicInteger calls = new AtomicInteger();
        AtomicFiles.replace(
                next,
                old,
                (a, b, options) -> {
                    if (calls.incrementAndGet() == 1)
                        throw new AtomicMoveNotSupportedException(
                                a.toString(), b.toString(), "test");
                    assertFalse(Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE));
                    Files.move(a, b, options);
                },
                ms -> fail("no lock delay"));
        assertEquals("new", Files.readString(old));
        assertEquals(2, calls.get());
    }

    @Test
    void otherIoFailuresAreNotRetried() throws Exception {
        Path old = original(), next = pending();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                IOException.class,
                () ->
                        AtomicFiles.replace(
                                next,
                                old,
                                (a, b, options) -> {
                                    calls.incrementAndGet();
                                    throw new IOException("full");
                                },
                                ms -> fail("no retry")));
        assertEquals(1, calls.get());
        assertEquals("old", Files.readString(old));
    }

    @Test
    void interruptionStopsRetriesAndRetainsInterruptFlag() throws Exception {
        Path old = original(), next = pending();
        try {
            assertThrows(
                    InterruptedIOException.class,
                    () ->
                            AtomicFiles.replace(
                                    next,
                                    old,
                                    (a, b, options) -> {
                                        throw new AccessDeniedException(b.toString());
                                    },
                                    ms -> {
                                        throw new InterruptedException();
                                    }));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("old", Files.readString(old));
        } finally {
            Thread.interrupted();
        }
    }
}
