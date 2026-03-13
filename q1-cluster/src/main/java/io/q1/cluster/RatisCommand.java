package io.q1.cluster;

import org.apache.ratis.protocol.Message;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Represents a mutation that travels through the Raft log.
 *
 * <h3>Binary encoding</h3>
 * <pre>
 * [1B]  type  0x01=PUT  0x02=DELETE  0x03=CREATE_BUCKET  0x04=DELETE_BUCKET
 * [2B]  bucket length (unsigned short)
 * [N B] bucket (UTF-8)
 * [2B]  key length (unsigned short)   — absent for CREATE/DELETE_BUCKET (set to 0)
 * [N B] key (UTF-8)                   — absent for CREATE/DELETE_BUCKET
 * [8B]  value length (long)           — PUT only
 * [N B] value bytes                   — PUT only
 * </pre>
 */
public record RatisCommand(Type type, String bucket, String key, byte[] value) {

    public enum Type {
        PUT((byte) 0x01), DELETE((byte) 0x02),
        CREATE_BUCKET((byte) 0x03), DELETE_BUCKET((byte) 0x04);

        final byte code;
        Type(byte code) { this.code = code; }

        static Type of(byte code) {
            for (Type t : values()) if (t.code == code) return t;
            throw new IllegalArgumentException("Unknown command type: " + code);
        }
    }

    // ── factories ─────────────────────────────────────────────────────────

    public static RatisCommand put(String bucket, String key, byte[] value) {
        return new RatisCommand(Type.PUT, bucket, key, value);
    }

    public static RatisCommand delete(String bucket, String key) {
        return new RatisCommand(Type.DELETE, bucket, key, null);
    }

    public static RatisCommand createBucket(String bucket) {
        return new RatisCommand(Type.CREATE_BUCKET, bucket, null, null);
    }

    public static RatisCommand deleteBucket(String bucket) {
        return new RatisCommand(Type.DELETE_BUCKET, bucket, null, null);
    }

    // ── serialisation ─────────────────────────────────────────────────────

    public Message toMessage() {
        return Message.valueOf(encode());
    }

    ByteString encode() {
        byte[] bBucket = bucket.getBytes(StandardCharsets.UTF_8);
        byte[] bKey    = (key != null) ? key.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int valueLen   = (value != null) ? value.length : 0;

        int size = 1 + 2 + bBucket.length + 2 + bKey.length;
        if (type == Type.PUT) size += 8 + valueLen;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(type.code);
        buf.putShort((short) bBucket.length);
        buf.put(bBucket);
        buf.putShort((short) bKey.length);
        buf.put(bKey);
        if (type == Type.PUT) {
            buf.putLong(valueLen);
            buf.put(value);
        }
        return ByteString.copyFrom(buf.array());
    }

    static RatisCommand decode(ByteString bytes) {
        ByteBuffer buf = bytes.asReadOnlyByteBuffer();

        Type   type    = Type.of(buf.get());
        String bucket  = readStr(buf);
        String key     = readStr(buf);
        byte[] value   = null;

        if (type == Type.PUT) {
            long vLen = buf.getLong();
            value = new byte[(int) vLen];
            buf.get(value);
        }

        return new RatisCommand(type, bucket, key.isEmpty() ? null : key, value);
    }

    private static String readStr(ByteBuffer buf) {
        int len = Short.toUnsignedInt(buf.getShort());
        if (len == 0) return "";
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
