package io.q1.core.io;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Creates {@link FileIO} instances for individual segment files.
 *
 * <p>Injected into {@link io.q1.core.Partition} so the I/O backend
 * (NIO vs io_uring) can be chosen at startup without touching any
 * storage logic.
 *
 * <p>Implementations may hold shared resources (e.g. a single
 * {@code io_uring} ring shared across all files in a partition).
 * They should be {@link java.io.Closeable} if they own such resources.
 */
@FunctionalInterface
public interface FileIOFactory {

    /**
     * Open (or create) the file at {@code path} and return a {@link FileIO}
     * ready for reading and writing.
     */
    FileIO open(Path path) throws IOException;
}
