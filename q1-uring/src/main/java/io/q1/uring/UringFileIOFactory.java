package io.q1.uring;

import io.q1.core.io.FileIO;
import io.q1.core.io.FileIOFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * {@link FileIOFactory} that creates {@link UringFileIO} instances backed by
 * a single shared {@link IoUring} ring.
 *
 * <p>Own the ring's lifecycle: call {@link #close()} when the partition
 * (or engine) shuts down.
 *
 * <p>To use:
 * <pre>{@code
 * try (UringFileIOFactory factory = new UringFileIOFactory(128)) {
 *     StorageEngine engine = new StorageEngine(dataDir, 16, factory);
 *     // ...
 * }
 * }</pre>
 */
public final class UringFileIOFactory implements FileIOFactory, Closeable {

    private final IoUring ring;
    private final Object  ringLock = new Object();

    /**
     * @param queueDepth number of SQE slots (must be a power of two, e.g. 64 or 128)
     */
    public UringFileIOFactory(int queueDepth) {
        this.ring = new IoUring(queueDepth);
    }

    @Override
    public FileIO open(Path path) throws IOException {
        return new UringFileIO(path, ring, ringLock);
    }

    @Override
    public void close() {
        ring.close();
    }
}
