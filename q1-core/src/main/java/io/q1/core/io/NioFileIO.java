package io.q1.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * {@link FileIO} backed by a {@link FileChannel}.
 *
 * {@code FileChannel} positional reads/writes are already thread-safe,
 * so no additional locking is required here.
 */
public final class NioFileIO implements FileIO {

    private final FileChannel channel;

    public NioFileIO(Path path) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
    }

    @Override
    public int read(ByteBuffer dst, long fileOffset) throws IOException {
        return channel.read(dst, fileOffset);
    }

    @Override
    public int write(ByteBuffer src, long fileOffset) throws IOException {
        return channel.write(src, fileOffset);
    }

    @Override
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void force() throws IOException {
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
