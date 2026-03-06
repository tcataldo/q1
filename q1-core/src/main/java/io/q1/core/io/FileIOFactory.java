package io.q1.core.io;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Creates {@link FileIO} instances for individual segment files.
 *
 * <p>Injected into {@link io.q1.core.Partition} so the I/O backend
 * can be chosen at startup without touching any storage logic.
 *
 * <p>Implementations may hold shared resources across files in a partition
 * and should be {@link java.io.Closeable} if they do.
 */
@FunctionalInterface
public interface FileIOFactory {

    /**
     * Open (or create) the file at {@code path} and return a {@link FileIO}
     * ready for reading and writing.
     */
    FileIO open(Path path) throws IOException;
}
