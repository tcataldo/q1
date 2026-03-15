package io.q1.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Test-only {@link FileIOFactory} that can be instructed to fail all writes,
 * simulating a full disk (ENOSPC).  Reads, force, and close always delegate
 * to the underlying factory so that in-flight data and clean-up are not
 * affected.
 *
 * <p>Usage:
 * <pre>
 *     FailingFileIOFactory ioFactory = new FailingFileIOFactory(NioFileIOFactory.INSTANCE);
 *     Partition partition = new Partition(0, dir, ioFactory, cache);
 *
 *     ioFactory.setFailWrites(true);
 *     assertThrows(IOException.class, () -> partition.put("b\u0000key", "val".getBytes()));
 *     ioFactory.setFailWrites(false);
 * </pre>
 */
public class FailingFileIOFactory implements FileIOFactory {

    private final FileIOFactory delegate;
    private volatile boolean failWrites = false;

    public FailingFileIOFactory(FileIOFactory delegate) {
        this.delegate = delegate;
    }

    public void setFailWrites(boolean fail) { this.failWrites = fail; }
    public boolean isFailWrites()           { return failWrites; }

    @Override
    public FileIO open(Path path) throws IOException {
        return new FailingFileIO(delegate.open(path));
    }

    private final class FailingFileIO implements FileIO {

        private final FileIO inner;

        FailingFileIO(FileIO inner) { this.inner = inner; }

        @Override
        public int read(ByteBuffer dst, long fileOffset) throws IOException {
            return inner.read(dst, fileOffset);
        }

        @Override
        public int write(ByteBuffer src, long fileOffset) throws IOException {
            if (failWrites) throw new IOException("Simulated disk full (ENOSPC)");
            return inner.write(src, fileOffset);
        }

        @Override public long size()  throws IOException { return inner.size(); }
        @Override public void force() throws IOException { inner.force(); }
        @Override public void close() throws IOException { inner.close(); }
    }
}
