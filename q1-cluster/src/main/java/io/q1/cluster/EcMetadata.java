package io.q1.cluster;

import java.util.Arrays;
import java.util.List;

/**
 * Per-object erasure-coding metadata stored in etcd.
 *
 * <h3>Wire format</h3>
 * {@code k|m|originalSize|shardSize|nodeWire0,nodeWire1,...}
 * where {@code nodeWire} is the {@link NodeId#toWire()} form {@code id:host:port}.
 * The {@code |} pipe is the record separator; {@code ,} separates node entries.
 *
 * <p>Example:
 * <pre>
 *   4|2|131072|32768|node0:10.0.0.1:9000,node1:10.0.0.2:9000,...
 * </pre>
 */
public record EcMetadata(
        int          k,
        int          m,
        long         originalSize,
        int          shardSize,
        List<String> nodeWireIds    // k+m entries, one per shard in shard-index order
) {

    public EcMetadata {
        nodeWireIds = List.copyOf(nodeWireIds);
        if (nodeWireIds.size() != k + m) {
            throw new IllegalArgumentException(
                    "nodeWireIds must have k+m=" + (k + m) + " entries, got " + nodeWireIds.size());
        }
    }

    public String toWire() {
        return k + "|" + m + "|" + originalSize + "|" + shardSize
                + "|" + String.join(",", nodeWireIds);
    }

    public static EcMetadata fromWire(String s) {
        String[] top = s.split("\\|", 5);
        if (top.length != 5) throw new IllegalArgumentException("Invalid EC metadata: " + s);
        int    k            = Integer.parseInt(top[0]);
        int    m            = Integer.parseInt(top[1]);
        long   originalSize = Long.parseLong(top[2]);
        int    shardSize    = Integer.parseInt(top[3]);
        List<String> nodes  = Arrays.asList(top[4].split(","));
        return new EcMetadata(k, m, originalSize, shardSize, nodes);
    }

    /** Rebuild the shard → node mapping from stored wire IDs. */
    public NodeId nodeForShard(int index) {
        return NodeId.fromWire(nodeWireIds.get(index));
    }
}
