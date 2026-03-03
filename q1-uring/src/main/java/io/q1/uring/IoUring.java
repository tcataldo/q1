package io.q1.uring;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Low-level bindings to <a href="https://github.com/axboe/liburing">liburing</a>
 * via the Java Foreign Function &amp; Memory API (Java 22+, stable).
 *
 * <h3>Status</h3>
 * This is a structural skeleton showing how we will replace Java NIO reads/writes
 * with io_uring once the data path is stable.  The struct layouts and method
 * handles are filled in against {@code liburing.h}; only the init/exit pair
 * is wired end-to-end for now.
 *
 * <h3>Building</h3>
 * Requires {@code liburing-dev} on the host:
 * <pre>apt install liburing-dev</pre>
 * Run with:
 * <pre>--enable-native-access=ALL-UNNAMED</pre>
 *
 * <h3>io_uring record format overview</h3>
 * <pre>
 *   io_uring_queue_init(entries, &amp;ring, flags)
 *
 *   sqe = io_uring_get_sqe(&amp;ring)
 *   io_uring_prep_read(sqe, fd, buf, len, offset)   // or prep_write, prep_readv …
 *   sqe->user_data = token
 *   io_uring_submit(&amp;ring)
 *
 *   io_uring_wait_cqe(&amp;ring, &amp;cqe)
 *   result = cqe->res                               // bytes read, or -errno
 *   io_uring_cqe_seen(&amp;ring, cqe)
 *
 *   io_uring_queue_exit(&amp;ring)
 * </pre>
 */
public final class IoUring implements AutoCloseable {

    // ── liburing symbol lookup ─────────────────────────────────────────────

    private static final Linker       LINKER;
    private static final SymbolLookup LIBURING;

    static {
        LINKER = Linker.nativeLinker();
        // liburing ships as liburing.so / liburing.so.2 on Linux
        LIBURING = SymbolLookup.libraryLookup("liburing", Arena.global());
    }

    // ── struct io_uring layout (from liburing.h / io_uring.h) ─────────────
    // Actual sizes depend on kernel version; these match liburing 2.x on x86-64.
    // Generated properly with jextract in production; hand-coded here for clarity.

    /** sizeof(struct io_uring) — opaque blob we allocate and pass by pointer. */
    private static final long SIZEOF_IO_URING = 216L;

    // ── method handles ─────────────────────────────────────────────────────

    private static final MethodHandle MH_QUEUE_INIT;
    private static final MethodHandle MH_QUEUE_EXIT;
    private static final MethodHandle MH_GET_SQE;
    private static final MethodHandle MH_SUBMIT;
    private static final MethodHandle MH_WAIT_CQE;
    private static final MethodHandle MH_CQE_SEEN;
    private static final MethodHandle MH_PREP_READ;
    private static final MethodHandle MH_PREP_WRITE;

    static {
        try {
            MH_QUEUE_INIT = LINKER.downcallHandle(
                    sym("io_uring_queue_init"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,   // entries
                            ValueLayout.ADDRESS,    // *ring
                            ValueLayout.JAVA_INT)); // flags

            MH_QUEUE_EXIT = LINKER.downcallHandle(
                    sym("io_uring_queue_exit"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)); // *ring

            MH_GET_SQE = LINKER.downcallHandle(
                    sym("io_uring_get_sqe"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,   // returns *sqe
                            ValueLayout.ADDRESS));               // *ring

            MH_SUBMIT = LINKER.downcallHandle(
                    sym("io_uring_submit"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS)); // *ring

            MH_WAIT_CQE = LINKER.downcallHandle(
                    sym("io_uring_wait_cqe"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,   // *ring
                            ValueLayout.ADDRESS)); // **cqe_ptr

            MH_CQE_SEEN = LINKER.downcallHandle(
                    sym("io_uring_cqe_seen"),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,   // *ring
                            ValueLayout.ADDRESS)); // *cqe

            MH_PREP_READ = LINKER.downcallHandle(
                    sym("io_uring_prep_read"),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,    // *sqe
                            ValueLayout.JAVA_INT,   // fd
                            ValueLayout.ADDRESS,    // buf
                            ValueLayout.JAVA_INT,   // nbytes
                            ValueLayout.JAVA_LONG)); // offset

            MH_PREP_WRITE = LINKER.downcallHandle(
                    sym("io_uring_prep_write"),
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,    // *sqe
                            ValueLayout.JAVA_INT,   // fd
                            ValueLayout.ADDRESS,    // buf
                            ValueLayout.JAVA_INT,   // nbytes
                            ValueLayout.JAVA_LONG)); // offset

        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ── instance ───────────────────────────────────────────────────────────

    private final Arena        arena;
    private final MemorySegment ring;   // *struct io_uring on the heap

    /**
     * Initialise an io_uring with {@code queueDepth} entries.
     * Typical values: 32–256.  Must be a power of two.
     */
    public IoUring(int queueDepth) {
        this.arena = Arena.ofConfined();
        this.ring  = arena.allocate(SIZEOF_IO_URING, 8);

        int rc;
        try {
            rc = (int) MH_QUEUE_INIT.invokeExact(queueDepth, ring, 0);
        } catch (Throwable t) {
            arena.close();
            throw new RuntimeException("io_uring_queue_init failed", t);
        }
        if (rc < 0) {
            arena.close();
            throw new RuntimeException("io_uring_queue_init returned " + rc);
        }
    }

    /**
     * Submit an async read of {@code length} bytes from {@code fd} at
     * {@code fileOffset} into {@code dest}.
     *
     * <p>The caller must call {@link #waitCqe()} to collect the result and
     * then {@link #cqeSeen(MemorySegment)} to release the CQE slot.
     */
    public void prepRead(int fd, MemorySegment dest, int length, long fileOffset) {
        MemorySegment sqe = getSqe();
        try {
            MH_PREP_READ.invokeExact(sqe, fd, dest, length, fileOffset);
            MH_SUBMIT.invokeExact(ring);
        } catch (Throwable t) {
            throw new RuntimeException("prepRead failed", t);
        }
    }

    public void prepWrite(int fd, MemorySegment src, int length, long fileOffset) {
        MemorySegment sqe = getSqe();
        try {
            MH_PREP_WRITE.invokeExact(sqe, fd, src, length, fileOffset);
            MH_SUBMIT.invokeExact(ring);
        } catch (Throwable t) {
            throw new RuntimeException("prepWrite failed", t);
        }
    }

    /**
     * Block until a completion event is available.
     *
     * @return the CQE pointer (pass to {@link #cqeResult} and {@link #cqeSeen})
     */
    public MemorySegment waitCqe() {
        MemorySegment cqePtr = arena.allocate(ValueLayout.ADDRESS);
        int rc;
        try {
            rc = (int) MH_WAIT_CQE.invokeExact(ring, cqePtr);
        } catch (Throwable t) {
            throw new RuntimeException("io_uring_wait_cqe failed", t);
        }
        if (rc < 0) throw new RuntimeException("io_uring_wait_cqe returned " + rc);
        return cqePtr.get(ValueLayout.ADDRESS, 0);
    }

    /** Result field of a CQE: bytes transferred (positive) or -errno (negative). */
    public int cqeResult(MemorySegment cqe) {
        // struct io_uring_cqe { __u64 user_data; __s32 res; __u32 flags; }
        // res is at offset 8
        return cqe.get(ValueLayout.JAVA_INT, 8);
    }

    public void cqeSeen(MemorySegment cqe) {
        try {
            MH_CQE_SEEN.invokeExact(ring, cqe);
        } catch (Throwable t) {
            throw new RuntimeException("io_uring_cqe_seen failed", t);
        }
    }

    @Override
    public void close() {
        try {
            MH_QUEUE_EXIT.invokeExact(ring);
        } catch (Throwable t) {
            throw new RuntimeException("io_uring_queue_exit failed", t);
        } finally {
            arena.close();
        }
    }

    // ── private ───────────────────────────────────────────────────────────

    private MemorySegment getSqe() {
        try {
            MemorySegment sqe = (MemorySegment) MH_GET_SQE.invokeExact(ring);
            if (MemorySegment.NULL.equals(sqe)) {
                throw new IllegalStateException("SQ ring full — call submit first");
            }
            return sqe;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("io_uring_get_sqe failed", t);
        }
    }

    private static MemorySegment sym(String name) {
        return LIBURING.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("liburing symbol not found: " + name));
    }
}
