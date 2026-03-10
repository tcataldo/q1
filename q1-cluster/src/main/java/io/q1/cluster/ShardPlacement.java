package io.q1.cluster;

import java.util.List;

/**
 * Maps each shard index (0..k+m-1) to the {@link NodeId} that stores it.
 *
 * <p>Computed by {@link EtcdCluster#computeShardPlacement(String, String)}
 * using a deterministic ring over the sorted active-node list.
 *
 * <p>Shards 0..k-1 are data shards; shards k..k+m-1 are parity shards.
 */
public record ShardPlacement(List<NodeId> nodes) {

    public ShardPlacement {
        nodes = List.copyOf(nodes);
    }

    /** The node responsible for shard at {@code index}. */
    public NodeId nodeForShard(int index) {
        return nodes.get(index);
    }

    /** All node IDs in shard-index order (wire-format strings for {@link EcMetadata}). */
    public List<String> nodeWireIds() {
        return nodes.stream().map(NodeId::toWire).toList();
    }

    public int totalShards() {
        return nodes.size();
    }
}
