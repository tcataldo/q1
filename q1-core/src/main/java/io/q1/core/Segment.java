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
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

/**
 * An append-only segment file.  Objects are packed sequentially; the file is
 * never modified in-place.  Deletes produce tombstone records.
 *
 * <p>Two record versions coexist for backward compatibility:
 *
 * <pre>
 * V1 (MAGIC 0x51310001) — legacy, no footer:
 *   [4B] MAGIC      0x51310001
 *   [1B] FLAGS      0x00 = DATA | 0x01 = TOMBSTONE
 *   [2B] KEY_LEN    unsigned short
 *   [8B] VAL_LEN    long  (0 for tombstones)
 *   [4B] CRC32      covers flags + key bytes + value bytes
 *   [KEY_LEN B]     key  (UTF-8)
 *   [VAL_LEN B]     value bytes  (absent for tombstones)
 *
 * V2 (MAGIC 0x51310002) — current, with footer:
 *   [4B] MAGIC      0x51310002
 *   [1B] FLAGS      0x00 = DATA | 0x01 = TOMBSTONE
 *   [2B] KEY_LEN    unsigned short
 *   [8B] VAL_LEN    long  (0 for tombstones)
 *   [4B] CRC32      covers flags + key bytes + value bytes  (header CRC)
 *   [KEY_LEN B]     key  (UTF-8)
 *   [VAL_LEN B]     value bytes  (absent for tombstones)
 *   [4B] CRC32      same value repeated                     (footer CRC)
 * </pre>
 *
 * <p>The footer allows {@link #scan} to detect VAL_LEN corruption and
 * partially-written records: if the length field is wrong, the footer
 * lands at the wrong file position and the CRC comparison fails before
 * the bad data is delivered to the index.  All new writes use V2; V1
 * records are accepted on read for backward compatibility with existing
 * segment files.
 *
 * <p>Reads are thread-safe (delegated to {@link FileIO} which guarantees this).
 * Writes are serialised via an internal lock; one Segment is always the
 * "active" writer per partition.
 *
 * The I/O backend is injected via {@link FileIO}.
 */
public final class Segment implements Closeable {

    /** Legacy record magic — no footer. */
    public static final int  MAGIC       = 0x51310001;
    /** Current record magic — footer CRC appended after value bytes. */
    public static final int  MAGIC_V2    = 0x51310002;
    public static final byte FLAG_DATA   = 0x00;
    public static final byte FLAG_TOMB   = 0x01;
    public static final int  HEADER_SIZE = 4 + 1 + 2 + 8 + 4; // 19 bytes
    /** Size of the footer CRC appended to every V2 record. */
    public static final int  FOOTER_SIZE = 4;

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
     * Read value bytes and verify the CRC32 stored in the record header.
     *
     * <p>When {@code keyLen} is negative (legacy index entry written before this
     * feature was added), the method falls back to a plain read with no CRC check.
     *
     * @param valueOffset byte offset where the value bytes start in this segment
     * @param valueLength number of value bytes
     * @param keyLen      UTF-8 byte length of the key, or {@code -1} for legacy entries
     * @throws IOException if the read fails or the CRC does not match
     */
    public byte[] read(long valueOffset, long valueLength, int keyLen) throws IOException {
        if (keyLen < 0) return read(valueOffset, valueLength); // legacy entry: no CRC available

        if (valueLength > Integer.MAX_VALUE - HEADER_SIZE - keyLen) {
            throw new IllegalArgumentException("Value too large: " + valueLength);
        }

        // Single I/O: read [header | key | value] in one call
        // Record layout: [4B magic][1B flags][2B keyLen][8B valLen][4B CRC][key][value]
        long   headerOffset = valueOffset - keyLen - HEADER_SIZE;
        int    totalLen     = HEADER_SIZE + keyLen + (int) valueLength;
        byte[] buf          = new byte[totalLen];
        readFully(ByteBuffer.wrap(buf), headerOffset);

        ByteBuffer header = ByteBuffer.wrap(buf, 0, HEADER_SIZE);
        header.getInt();                       // magic — validated at write time
        byte flags    = header.get();
        header.getShort();                     // keyLen field — already known
        header.getLong();                      // valLen field — already known
        int storedCrc = header.getInt();

        CRC32 crc = new CRC32();
        crc.update(flags);
        crc.update(buf, HEADER_SIZE, keyLen);          // key bytes
        crc.update(buf, HEADER_SIZE + keyLen, (int) valueLength); // value bytes
        if ((int) crc.getValue() != storedCrc) {
            throw new IOException(
                    "CRC mismatch in segment " + id + " at valueOffset=" + valueOffset +
                    " (stored=0x" + Integer.toHexString(storedCrc) +
                    " computed=0x" + Integer.toHexString((int) crc.getValue()) + ")");
        }

        // Return value slice (avoids an extra copy — just wrap the sub-range)
        return Arrays.copyOfRange(buf, HEADER_SIZE + keyLen, totalLen);
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
            if (magic != MAGIC && magic != MAGIC_V2) {
                throw new IOException(
                        "Corrupt segment " + path + " at offset " + pos +
                        " (bad magic 0x" + Integer.toHexString(magic) + ")");
            }
            byte flags     = header.get();
            int  keyLen    = Short.toUnsignedInt(header.getShort());
            long valLen    = header.getLong();
            int  storedCrc = header.getInt();

            byte[]     kb   = new byte[keyLen];
            ByteBuffer kbuf = ByteBuffer.wrap(kb);
            readFully(kbuf, pos + HEADER_SIZE);
            String key = new String(kb, StandardCharsets.UTF_8);

            long valueOffset = pos + HEADER_SIZE + keyLen;

            byte[] valueBytes = new byte[0];
            if (valLen > 0) {
                if (valLen > Integer.MAX_VALUE)
                    throw new IOException("Value too large to CRC-verify: " + valLen);
                valueBytes = new byte[(int) valLen];
                readFully(ByteBuffer.wrap(valueBytes), valueOffset);
            }

            CRC32 crc = new CRC32();
            crc.update(flags);
            crc.update(kb);
            crc.update(valueBytes);
            if ((int) crc.getValue() != storedCrc) {
                throw new IOException(
                        "CRC mismatch in segment " + path + " at offset " + pos +
                        " for key \"" + key + "\" (stored=0x" +
                        Integer.toHexString(storedCrc) + " computed=0x" +
                        Integer.toHexString((int) crc.getValue()) + ")");
            }

            if (magic == MAGIC_V2) {
                ByteBuffer footer = ByteBuffer.allocate(FOOTER_SIZE);
                readFully(footer, valueOffset + valLen);
                footer.flip();
                int footerCrc = footer.getInt();
                if (footerCrc != storedCrc) {
                    throw new IOException(
                            "Footer CRC mismatch in segment " + path +
                            " at offset " + (valueOffset + valLen) +
                            " for key \"" + key + "\" (stored=0x" +
                            Integer.toHexString(storedCrc) + " footer=0x" +
                            Integer.toHexString(footerCrc) + ")");
                }
            }

            visitor.visit(key, flags, id, valueOffset, valLen);

            pos = valueOffset + valLen + (magic == MAGIC_V2 ? FOOTER_SIZE : 0);
        }
    }

    /** Flush all pending writes to the underlying storage device. */
    public void force() throws IOException {
        io.force();
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
            if (magic != MAGIC && magic != MAGIC_V2) throw new IOException(
                    "Corrupt sync stream: bad magic 0x" + Integer.toHexString(magic));

            byte  flags     = header.get();
            int   keyLen    = Short.toUnsignedInt(header.getShort());
            long  valLen    = header.getLong();
            int   storedCrc = header.getInt();

            byte[] keyBytes = dis.readNBytes(keyLen);
            if (keyBytes.length < keyLen) break;         // truncated
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            byte[] value = valLen > 0 ? dis.readNBytes((int) valLen) : new byte[0];
            if (value.length < valLen) break;            // truncated

            CRC32 crc = new CRC32();
            crc.update(flags);
            crc.update(keyBytes);
            crc.update(value);
            if ((int) crc.getValue() != storedCrc) {
                throw new IOException(
                        "CRC mismatch in sync stream for key \"" + key +
                        "\" (stored=0x" + Integer.toHexString(storedCrc) +
                        " computed=0x" + Integer.toHexString((int) crc.getValue()) + ")");
            }

            if (magic == MAGIC_V2) {
                byte[] footerBytes = dis.readNBytes(FOOTER_SIZE);
                if (footerBytes.length < FOOTER_SIZE) break;  // truncated — stop safely
                int footerCrc = ByteBuffer.wrap(footerBytes).getInt();
                if (footerCrc != storedCrc) {
                    throw new IOException(
                            "Footer CRC mismatch in sync stream for key \"" + key +
                            "\" (stored=0x" + Integer.toHexString(storedCrc) +
                            " footer=0x" + Integer.toHexString(footerCrc) + ")");
                }
            }

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
        int crcValue = (int) crc.getValue();

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + keyBytes.length + value.length + FOOTER_SIZE);
        buf.putInt(MAGIC_V2);
        buf.put(flags);
        buf.putShort((short) keyBytes.length);
        buf.putLong(value.length);
        buf.putInt(crcValue);
        buf.put(keyBytes);
        buf.put(value);
        buf.putInt(crcValue);   // footer: same CRC repeated after value bytes
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
