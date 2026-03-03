package io.q1.uring;

import io.q1.core.io.FileIO;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * {@link FileIO} that uses Linux io_uring for data reads and writes, and
 * the POSIX {@code open}/{@code lseek}/{@code fsync}/{@code close} syscalls
 * (via Panama FFI) for file lifecycle.
 *
 * <h3>Thread safety</h3>
 * The underlying {@link IoUring} ring is shared across all files in the same
 * partition.  A coarse {@code synchronized} block protects submission +
 * completion pairs so concurrent virtual threads don't interleave SQEs.
 * A production implementation would use per-thread rings or io_uring's
 * built-in SQ polling ({@code IORING_SETUP_SQPOLL}).
 *
 * <h3>Buffer strategy</h3>
 * Each read/write allocates a short-lived confined arena for the off-heap
 * buffer.  For email-scale objects (≤128 KiB) this is cheap.  A buffer-pool
 * is a natural next step.
 */
public final class UringFileIO implements FileIO {

    // ── POSIX syscall handles (loaded once per JVM) ───────────────────────

    private static final Linker       LIBC;
    private static final MethodHandle MH_OPEN;
    private static final MethodHandle MH_LSEEK;
    private static final MethodHandle MH_FSYNC;
    private static final MethodHandle MH_CLOSE;

    // Linux x86-64 constants
    private static final int O_RDWR   = 2;
    private static final int O_CREAT  = 64;    // 0100 octal
    private static final int O_TRUNC  = 512;   // 01000 octal — NOT used; we open for append
    private static final int MODE_644 = 0644;
    private static final int SEEK_END = 2;

    static {
        LIBC = Linker.nativeLinker();
        SymbolLookup stdlib = LIBC.defaultLookup();
        try {
            // open(const char *pathname, int flags, ...) — we always pass mode as 3rd arg
            MH_OPEN = LIBC.downcallHandle(
                    sym(stdlib, "open"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,  // pathname
                            ValueLayout.JAVA_INT, // flags
                            ValueLayout.JAVA_INT),// mode  (first variadic arg)
                    Linker.Option.firstVariadicArg(2));

            // off_t lseek(int fd, off_t offset, int whence)
            MH_LSEEK = LIBC.downcallHandle(
                    sym(stdlib, "lseek"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT));

            // int fsync(int fd)
            MH_FSYNC = LIBC.downcallHandle(
                    sym(stdlib, "fsync"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));

            // int close(int fd)
            MH_CLOSE = LIBC.downcallHandle(
                    sym(stdlib, "close"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));

        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ── instance state ────────────────────────────────────────────────────

    private final int     fd;
    private final IoUring ring;
    private final Object  ringLock; // shared with all UringFileIO in the same factory

    /** Called by {@link UringFileIOFactory}. */
    UringFileIO(Path path, IoUring ring, Object ringLock) throws IOException {
        this.ring     = ring;
        this.ringLock = ringLock;
        this.fd       = openFile(path);
    }

    // ── FileIO ────────────────────────────────────────────────────────────

    @Override
    public int read(ByteBuffer dst, long fileOffset) throws IOException {
        int remaining = dst.remaining();
        if (remaining == 0) return 0;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(remaining);

            int result;
            synchronized (ringLock) {
                ring.prepRead(fd, buf, remaining, fileOffset);
                MemorySegment cqe = ring.waitCqe();
                result = ring.cqeResult(cqe);
                ring.cqeSeen(cqe);
            }

            if (result == 0)  return -1;              // EOF
            if (result < 0)   throw new IOException("io_uring read failed: errno " + (-result));

            // Off-heap → heap ByteBuffer
            byte[] tmp = new byte[result];
            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, tmp, 0, result);
            dst.put(tmp, 0, result);
            return result;
        }
    }

    @Override
    public int write(ByteBuffer src, long fileOffset) throws IOException {
        int remaining = src.remaining();
        if (remaining == 0) return 0;

        try (Arena arena = Arena.ofConfined()) {
            // Heap ByteBuffer → off-heap (don't advance src yet; do it after success)
            byte[] tmp = new byte[remaining];
            src.duplicate().get(tmp);

            MemorySegment buf = arena.allocate(remaining);
            MemorySegment.copy(tmp, 0, buf, ValueLayout.JAVA_BYTE, 0, remaining);

            int result;
            synchronized (ringLock) {
                ring.prepWrite(fd, buf, remaining, fileOffset);
                MemorySegment cqe = ring.waitCqe();
                result = ring.cqeResult(cqe);
                ring.cqeSeen(cqe);
            }

            if (result < 0) throw new IOException("io_uring write failed: errno " + (-result));
            src.position(src.position() + result);  // advance only on success
            return result;
        }
    }

    @Override
    public long size() throws IOException {
        try {
            long pos = (long) MH_LSEEK.invokeExact(fd, 0L, SEEK_END);
            if (pos < 0) throw new IOException("lseek(SEEK_END) failed: errno " + (-pos));
            return pos;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("lseek failed", t);
        }
    }

    @Override
    public void force() throws IOException {
        try {
            int rc = (int) MH_FSYNC.invokeExact(fd);
            if (rc != 0) throw new IOException("fsync failed: " + rc);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("fsync failed", t);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            int rc = (int) MH_CLOSE.invokeExact(fd);
            if (rc != 0) throw new IOException("close failed: " + rc);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("close failed", t);
        }
    }

    // ── private helpers ───────────────────────────────────────────────────

    private static int openFile(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = arena.allocateFrom(path.toAbsolutePath().toString());
            int fd = (int) MH_OPEN.invokeExact(pathSeg, O_RDWR | O_CREAT, MODE_644);
            if (fd < 0) throw new IOException("open() failed for " + path + " (errno " + (-fd) + ")");
            return fd;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("open() failed for " + path, t);
        }
    }

    private static MemorySegment sym(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("libc symbol not found: " + name));
    }
}
