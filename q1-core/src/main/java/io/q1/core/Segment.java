package io.q1.core;

import io.q1.core.io.FileIO;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

/**
 * An append-only segment file.  Objects are packed sequentially; the file is
 * never modified in-place.  Deletes produce tombstone records.
 *
 * <pre>
 * Record layout (19-byte fixed header + variable body):
 *   [4B] MAGIC      0x51310001
 *   [1B] FLAGS      0x00 = DATA | 0x01 = TOMBSTONE
 *   [2B] KEY_LEN    unsigned short
 *   [8B] VAL_LEN    long  (0 for tombstones)
 *   [4B] CRC32      covers flags + key bytes + value bytes
 *   [KEY_LEN B]     key  (UTF-8)
 *   [VAL_LEN B]     value bytes  (absent for tombstones)
 * </pre>
 *
 * Reads are thread-safe (delegated to {@link FileIO} which guarantees this).
 * Writes are serialised via an internal lock; one Segment is always the
 * "active" writer per partition.
 *
 * The I/O backend is injected via {@link FileIO}:
 * swap in {@code UringFileIO} from {@code q1-uring} for io_uring-backed I/O.
 */
public final class Segment implements Closeable {

    public static final int  MAGIC       = 0x51310001;
    public static final byte FLAG_DATA   = 0x00;
    public static final byte FLAG_TOMB   = 0x01;
    public static final int  HEADER_SIZE = 4 + 1 + 2 + 8 + 4; // 19 bytes

    private final int    id;
    private final Path   path;
    private final FileIO io;

    private       long          writePos;
    private final ReentrantLock writeLock = new ReentrantLock();

    /** Open (or create) a segment, using the supplied I/O backend. */
    public Segment(int id, Path path, FileIO io) throws IOException {
        this.id       = id;
        this.path     = path;
        this.io       = io;
        this.writePos = io.size();
    }

    public int  id()   { return id; }
    public Path path() { return path; }
    public long size() { return writePos; }

    /**
     * Append a DATA record.
     *
     * @return the byte offset within this segment at which the VALUE bytes start,
     *         suitable for storing directly in the index.
     */
    public long append(String key, byte[] value) throws IOException {
        byte[]     keyBytes = keyBytes(key);
        ByteBuffer buf      = buildRecord(FLAG_DATA, keyBytes, value);

        writeLock.lock();
        try {
            long valueOffset = writePos + HEADER_SIZE + keyBytes.length;
            writeFully(buf);
            return valueOffset;
        } finally {
            writeLock.unlock();
        }
    }

    /** Append a TOMBSTONE record for the given key. */
    public void appendTombstone(String key) throws IOException {
        byte[]     keyBytes = keyBytes(key);
        ByteBuffer buf      = buildRecord(FLAG_TOMB, keyBytes, new byte[0]);

        writeLock.lock();
        try {
            writeFully(buf);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Read {@code length} value bytes starting at {@code offset}.
     * Delegates to {@link FileIO#read} which must be thread-safe.
     */
    public byte[] read(long offset, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Value too large: " + length);
        }
        byte[]     data = new byte[(int) length];
        ByteBuffer bb   = ByteBuffer.wrap(data);
        long       pos  = offset;
        while (bb.hasRemaining()) {
            int n = io.read(bb, pos);
            if (n < 0) throw new EOFException("Unexpected EOF in segment " + id + " at " + pos);
            pos += n;
        }
        return data;
    }

    /**
     * Scan every record from the beginning of this segment.
     * Used to rebuild the in-memory index on startup.
     */
    public void scan(RecordVisitor visitor) throws IOException {
        long       pos    = 0;
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);

        while (pos < writePos) {
            header.clear();
            readFully(header, pos);
            header.flip();

            int magic = header.getInt();
            if (magic != MAGIC) {
                throw new IOException(
                        "Corrupt segment " + path + " at offset " + pos +
                        " (bad magic 0x" + Integer.toHexString(magic) + ")");
            }
            byte flags  = header.get();
            int  keyLen = Short.toUnsignedInt(header.getShort());
            long valLen = header.getLong();
            /* int crc = */ header.getInt(); // TODO: verify CRC on read

            byte[]     kb   = new byte[keyLen];
            ByteBuffer kbuf = ByteBuffer.wrap(kb);
            readFully(kbuf, pos + HEADER_SIZE);
            String key = new String(kb, StandardCharsets.UTF_8);

            long valueOffset = pos + HEADER_SIZE + keyLen;
            visitor.visit(key, flags, id, valueOffset, valLen);

            pos = valueOffset + valLen;
        }
    }

    @Override
    public void close() throws IOException {
        io.force();
        io.close();
    }

    /**
     * Parse records from a raw byte stream (e.g. a leader sync response).
     * Uses the same on-disk format as {@link #scan}.
     * Stops cleanly at EOF; truncated trailing records are silently ignored.
     *
     * @param in      the byte source (caller is responsible for closing)
     * @param visitor called once per complete record
     */
    public static void scanStream(InputStream in, SyncRecordVisitor visitor) throws IOException {
        DataInputStream dis    = new DataInputStream(in);
        byte[]          hBuf   = new byte[HEADER_SIZE];

        while (true) {
            int hRead = dis.readNBytes(hBuf, 0, HEADER_SIZE);
            if (hRead == 0) break;                       // clean EOF
            if (hRead < HEADER_SIZE) break;              // truncated — stop safely

            ByteBuffer header = ByteBuffer.wrap(hBuf);
            int magic = header.getInt();
            if (magic != MAGIC) throw new IOException(
                    "Corrupt sync stream: bad magic 0x" + Integer.toHexString(magic));

            byte  flags  = header.get();
            int   keyLen = Short.toUnsignedInt(header.getShort());
            long  valLen = header.getLong();
            /* int crc = */ header.getInt();             // TODO: verify CRC

            byte[] keyBytes = dis.readNBytes(keyLen);
            if (keyBytes.length < keyLen) break;         // truncated
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            byte[] value = valLen > 0 ? dis.readNBytes((int) valLen) : new byte[0];
            if (value.length < valLen) break;            // truncated

            visitor.visit(key, flags, value);
        }
    }

    // ── private helpers ───────────────────────────────────────────────────

    private static byte[] keyBytes(String key) {
        byte[] b = key.getBytes(StandardCharsets.UTF_8);
        if (b.length > 65535) throw new IllegalArgumentException("Key exceeds 65535 bytes: " + key);
        return b;
    }

    private static ByteBuffer buildRecord(byte flags, byte[] keyBytes, byte[] value) {
        CRC32 crc = new CRC32();
        crc.update(flags);
        crc.update(keyBytes);
        crc.update(value);

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + keyBytes.length + value.length);
        buf.putInt(MAGIC);
        buf.put(flags);
        buf.putShort((short) keyBytes.length);
        buf.putLong(value.length);
        buf.putInt((int) crc.getValue());
        buf.put(keyBytes);
        buf.put(value);
        buf.flip();
        return buf;
    }

    /** Write {@code buf} at the current write position (write lock must be held). */
    private void writeFully(ByteBuffer buf) throws IOException {
        long pos   = writePos;
        int  total = buf.remaining();
        while (buf.hasRemaining()) {
            pos += io.write(buf, pos);
        }
        writePos += total;
    }

    private void readFully(ByteBuffer buf, long startPos) throws IOException {
        long pos = startPos;
        while (buf.hasRemaining()) {
            int n = io.read(buf, pos);
            if (n < 0) throw new EOFException("Unexpected EOF in segment " + id + " at " + pos);
            pos += n;
        }
    }
}
