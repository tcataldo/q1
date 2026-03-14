# q1-api — S3-compatible HTTP Server

Depends on `q1-core` and `q1-cluster`. This is the process entry point.

## Responsibilities

- Undertow HTTP server on virtual threads
- S3 path-style routing (`/{bucket}/{key}`)
- Object operations: GET, PUT, HEAD, DELETE
- Bucket operations: create, list, delete, list-buckets
- `/healthz` endpoint (always responds before cluster ready check)
- Transparent proxy of writes to the Raft leader (non-leader nodes)
- EC object fan-out via `EcObjectHandler` when `Q1_EC_K > 0`

## Routing

```
GET  /healthz                  → HealthHandler (200/503, JSON)
GET  /                         → listBuckets
PUT  /{bucket}                 → createBucket   (Raft: CREATE_BUCKET)
GET  /{bucket}?prefix=…        → listObjects    (RocksDB, pagination)
DELETE /{bucket}               → deleteBucket   (Raft: DELETE_BUCKET)
PUT  /{bucket}/{key}           → put object     (Raft replication or EC fan-out)
GET  /{bucket}/{key}           → get object     (local read, eventual consistency)
HEAD /{bucket}/{key}           → head object
DELETE /{bucket}/{key}         → delete object  (Raft replication or EC fan-out)
PUT  /internal/v1/shards/…     → ShardHandler   (EC internal shard endpoint)
GET  /internal/v1/shards/…     → ShardHandler
HEAD /internal/v1/shards/…     → ShardHandler
DELETE /internal/v1/shards/…   → ShardHandler
```

## Startup modes

**Standalone** (`Q1_PEERS` absent):
```
Q1Server(engine, port)
```
All requests served locally, no replication.

**Cluster — replication mode** (`Q1_PEERS` present, `Q1_EC_K` absent or 0):
```
Q1Server(engine, cluster, partitionRouter, port)
```
- PUT/DELETE → leader via `cluster.submit(RatisCommand)` (Raft commit)
- Non-leader nodes proxy to the leader transparently (`proxyToLeader()`)

**Cluster — EC mode** (`Q1_PEERS` present, `Q1_EC_K > 0`):
```
Q1Server(engine, cluster, partitionRouter, coder, shardClient, port)
```
- PUT → `EcObjectHandler`: RS encode → HTTP fan-out to k+m nodes (`HttpShardClient`)
- GET → fetch available shards, RS decode if any missing
- Raft used only for bucket ops and topology (`config.peers()`)

Startup sequence (cluster modes):
1. `RatisCluster.start()` — Raft starts, leader elected automatically
2. `Q1Server.start()` — HTTP port opened; `EcRepairScanner` started if EC mode

## Key files

| File | Role |
|---|---|
| `Q1Server.java` | Entry point, `main()`, construction from env vars, `close()` ordering |
| `S3Router.java` | URL parsing, mode detection, `proxyToLeader()` |
| `handler/ObjectHandler.java` | PUT/GET/HEAD/DELETE (replication mode) |
| `handler/EcObjectHandler.java` | PUT/GET/HEAD/DELETE (EC mode) |
| `handler/BucketHandler.java` | Bucket ops + `sendXml`, `sendError` helpers |
| `handler/ShardHandler.java` | Internal shard endpoint (EC fan-out target) |
| `handler/HealthHandler.java` | `/healthz` — JSON status, 200/503 |
| `handler/ListingHandler.java` | ListObjectsV1/V2 with delimiter + pagination |
| `ec/EcRepairScanner.java` | Background shard repair loop |
| `ec/HttpShardClient.java` | HTTP client for shard fan-out |

## Gotchas

- `exchange.startBlocking()` required before `exchange.getInputStream()` (Undertow)
- `BucketHandler.sendError` must be `public` (called from `S3Router` across packages)
- `Q1Server.close()` already closes `cluster` and `engine` — do NOT close them separately
  or you'll get `ClosedChannelException` (double-close of FileChannel)
- Each IT test class must use a distinct `ClusterConfig.raftGroupId` to avoid Ratis
  background thread interference across test classes in the same JVM

## AWS SDK gotcha

The AWS SDK v2 uses `aws-chunked` encoding by default for PUTs. Disable on the client:
```java
S3Configuration.builder().chunkedEncodingEnabled(false)...
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `Q1_NODE_ID` | `node-{random8}` | Unique name (must match a peer ID in `Q1_PEERS`) |
| `Q1_HOST` | `localhost` | Advertised HTTP hostname/IP |
| `Q1_PORT` | `9000` | HTTP listen port |
| `Q1_DATA_DIR` | `q1-data` | Data directory |
| `Q1_RAFT_PORT` | `6000` | Raft gRPC port |
| `Q1_PEERS` | _(absent = standalone)_ | `id\|host\|httpPort\|raftPort`, comma-separated |
| `Q1_PARTITIONS` | `16` | Number of partitions |
| `Q1_EC_K` | `0` (disabled) | EC data shards; 0 = plain Raft replication |
| `Q1_EC_M` | `2` | EC parity shards |
| `Q1_REPAIR_INTERVAL_S` | `60` | EC repair scanner interval |
| `Q1_REPAIR_BATCH_SIZE` | `200` | EC repair keys per pass |

## TODO

- [ ] SigV4 signature validation (currently any key/secret is accepted)
- [ ] Persistent Content-Type, ETag, Last-Modified, real Size in listings
- [ ] Multipart upload (> 5 GB)
- [ ] Rate limiting / circuit breaker
- [ ] Prometheus `/metrics` endpoint
