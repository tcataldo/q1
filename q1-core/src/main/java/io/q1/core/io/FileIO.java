package io.q1.core.io;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Abstraction over a single readable/writable file used by {@link io.q1.core.Segment}.
 *
 * <p>Semantics mirror {@link java.nio.channels.FileChannel} positional I/O:
 * <ul>
 *   <li>{@link #read} and {@link #write} advance {@code buf.position()} by the
 *       number of bytes transferred and return that count.</li>
 *   <li>Both are safe to call from multiple virtual threads simultaneously
 *       (implementations must guarantee this).</li>
 * </ul>
 *
 * Two implementations ship out of the box:
 * <ul>
 *   <li>{@link NioFileIO} — backed by {@code FileChannel}, used by default.</li>
 *   <li>{@code io.q1.uring.UringFileIO} — backed by io_uring for kernel-bypass
 *       async I/O on Linux.</li>
 * </ul>
 */
public interface FileIO extends Closeable {

    /**
     * Read up to {@code dst.remaining()} bytes from the file at {@code fileOffset}
     * into {@code dst}.  Advances {@code dst.position()} by the number of bytes read.
     *
     * @return number of bytes read, or {@code -1} on EOF
     */
    int read(ByteBuffer dst, long fileOffset) throws IOException;

    /**
     * Write up to {@code src.remaining()} bytes from {@code src} into the file at
     * {@code fileOffset}.  Advances {@code src.position()} by the number of bytes written.
     *
     * @return number of bytes written
     */
    int write(ByteBuffer src, long fileOffset) throws IOException;

    /** Current file size in bytes. */
    long size() throws IOException;

    /** Flush data and metadata to durable storage (fsync). */
    void force() throws IOException;

    @Override
    void close() throws IOException;
}
