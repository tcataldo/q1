# Q1 Internal gRPC API

> S3 is Amazon's, R2 is Cloudflare's, Q1 is ours — and inter-node calls should be fast.

## Why gRPC

The original inter-node transport for EC shard fan-out is plain HTTP
(`HttpShardClient` → `ShardHandler`).  HTTP works but carries unnecessary overhead
for internal calls: text headers, chunked encoding negotiation, a full TCP round-trip
per shard.  gRPC over HTTP/2 gives us binary framing, multiplexing, and strongly-typed
contracts for free.

The second driver is the **admin CLI** (`q1-admin`, not yet built): a gRPC service is a
much cleaner surface than a REST API for operational commands (drain a node, trigger
compaction, inspect Raft state, …).

## Module: `q1-api-grpc`

```
q1-api-grpc/
  src/main/proto/io/q1/grpc/
    q1_internal.proto          # service definitions
  src/main/java/io/q1/grpc/
    ShardServiceImpl.java      # server: shard CRUD backed by StorageEngine
    AdminServiceImpl.java      # server: health + node info
    GrpcShardClient.java       # client: ShardClient impl over gRPC
    GrpcServer.java            # lifecycle wrapper (start / close)
  src/test/java/io/q1/grpc/
    GrpcShardClientTest.java   # 8 unit tests via in-process server
```

### Dependency graph

```
q1-core
  └── q1-cluster   (Raft, ShardClient interface, ShardStorage constants)
  └── q1-api-grpc  (gRPC stubs + impls — depends on q1-core + q1-cluster)
  └── q1-api       (HTTP server — depends on q1-cluster + q1-api-grpc)
```

`q1-api-grpc` never depends on `q1-api` to avoid a cycle.

## Proto services

### `ShardService`

Replaces the HTTP `PUT/GET/HEAD/DELETE /internal/v1/shard/…` endpoint for
inter-node EC shard fan-out.

```proto
service ShardService {
  rpc PutShard    (PutShardRequest)    returns (PutShardResponse);
  rpc GetShard    (GetShardRequest)    returns (GetShardResponse);
  rpc HeadShard   (HeadShardRequest)   returns (HeadShardResponse);
  rpc DeleteShard (DeleteShardRequest) returns (DeleteShardResponse);
}
```

Payload format is identical to the HTTP path: an 8-byte big-endian `originalSize`
header followed by the raw Reed-Solomon shard bytes (see `EcObjectHandler`).

### `AdminService`

Operational surface for the future admin CLI.

```proto
service AdminService {
  rpc GetHealth   (HealthRequest)   returns (HealthResponse);
  rpc GetNodeInfo (NodeInfoRequest) returns (NodeInfoResponse);
}
```

`GetHealth` returns `HEALTHY` / `DEGRADED` and the current leader status.
`GetNodeInfo` returns the full `NodeId` (HTTP, Raft, gRPC ports) and partition count.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `Q1_GRPC_PORT` | `7000` | gRPC listen port (all modes) |

The gRPC port is also part of the `Q1_PEERS` wire format (5th field):

```
Q1_PEERS=node1|10.0.0.1|9000|6000|7000,node2|10.0.0.2|9000|6000|7000,...
```

The legacy 4-field format (`id|host|httpPort|raftPort`) is still accepted; the gRPC
port defaults to `raftPort + 1000` in that case.

## Current state — Phase 1

- [x] Proto definitions (`ShardService`, `AdminService`)
- [x] `ShardServiceImpl` — server-side shard ops (uses `ShardStorage` constants
      shared with `ShardHandler`)
- [x] `AdminServiceImpl` — health + node info
- [x] `GrpcShardClient` — `ShardClient` implementation over gRPC; one
      `ManagedChannel` per peer, 5 s deadline per call
- [x] `GrpcServer` — started on every node (standalone + cluster); stopped before
      `StorageEngine` is closed
- [x] `NodeId` extended with `grpcPort` (5th field, backward-compatible)
- [x] `ShardClient` interface extracted in `q1-cluster`; `HttpShardClient` implements
      it — transport is swappable without touching `EcObjectHandler` or
      `EcRepairScanner`
- [x] 8 unit tests for `GrpcShardClient` via gRPC in-process server

**The EC fan-out still uses `HttpShardClient` by default.**  Switching is a one-line
change in `Q1Server.main()`:

```java
// current (HTTP)
ShardClient shardClient = new HttpShardClient();

// switch to gRPC
ShardClient shardClient = new GrpcShardClient(peers);
```

## Roadmap — Phase 2: switch EC fan-out to gRPC

1. Replace `new HttpShardClient()` with `new GrpcShardClient(peers)` in
   `Q1Server.main()`.
2. Close `GrpcShardClient` in `Q1Server.close()` (it implements `Closeable`).
3. Add an integration test (`EcGrpcIT`) that exercises a 3-node EC cluster end-to-end
   over the gRPC transport.
4. Keep `ShardHandler` (HTTP) active for rolling upgrades: nodes on older builds can
   still reach new nodes over HTTP while the cluster is being upgraded.

## Roadmap — Phase 3: admin CLI (`q1-admin`)

The `AdminService` proto is designed to be the foundation of a standalone admin CLI.

Planned commands:

| Command | Proto RPC | Description |
|---|---|---|
| `q1-admin health <node>` | `GetHealth` | Check node status |
| `q1-admin info <node>` | `GetNodeInfo` | Dump node configuration |
| `q1-admin compact <node>` | `TriggerCompaction` (future) | Force compaction on a node |
| `q1-admin drain <node>` | `DrainNode` (future) | Gracefully remove a node |
| `q1-admin raft-status` | `GetRaftStatus` (future) | Term, commit index, peer lag |

The CLI will live in a new Maven module `q1-admin` that depends only on `q1-api-grpc`
(for the generated stubs) and a gRPC channel builder.  No dependency on `q1-api` or
`q1-cluster` — it talks to a running cluster over the network.

## Roadmap — Phase 4: extend the proto surface

Operations that could move to gRPC as the cluster matures:

- **Bucket replication** — `BucketService.CreateBucket` / `DeleteBucket` broadcast
  to all peers, fixing the current limitation where bucket ops only affect the local
  node.
- **Sync stream** — `SyncService.StreamPartition` (server-streaming RPC) as a faster
  replacement for the HTTP sync endpoint, with back-pressure via flow control.
- **Raft metrics** — `AdminService.GetRaftStatus` exposing term, commit index, and
  per-peer replication lag for the `/metrics` Prometheus endpoint.

## Testing

```bash
# Unit tests only (in-process, no network)
mvn test -pl q1-api-grpc

# Full build including integration tests
mvn verify -pl q1-core,q1-cluster,q1-api-grpc,q1-api,q1-tests --also-make
```

The `GrpcShardClientTest` uses `InProcessServerBuilder` / `InProcessChannelBuilder`
from `grpc-inprocess`: the full serialisation and service-dispatch path is exercised
without any real socket.
