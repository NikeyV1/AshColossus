package de.nikey.witherboss.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;

public final class AtomicFiles {
    private AtomicFiles() {}

    @FunctionalInterface
    interface Move {
        void run(Path source, Path target, CopyOption... options) throws IOException;
    }

    @FunctionalInterface
    interface Delay {
        void run(long millis) throws InterruptedException;
    }

    public static void replace(Path source, Path target) throws IOException {
        replace(source, target, Files::move, Thread::sleep);
    }

    static void replace(Path source, Path target, Move move, Delay delay) throws IOException {
        boolean atomic = true;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (atomic)
                    move.run(
                            source,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                else move.run(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException unsupported) {
                if (!atomic) throw unsupported;
                atomic = false;
                attempt--;
            } catch (AccessDeniedException locked) {
                if (attempt == 4) throw locked;
                try {
                    delay.run(10L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure =
                            new InterruptedIOException("Dateiaustausch unterbrochen.");
                    failure.initCause(interrupted);
                    throw failure;
                }
            }
        }
    }
}
