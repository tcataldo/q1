# q1-api — S3-compatible HTTP Server

Depends on `q1-core` and `q1-cluster`. This is the process entry point.

## Responsibilities

- Undertow HTTP server on virtual threads
- S3 path-style routing (`/{bucket}/{key}`)
- Object operations: GET, PUT, HEAD, DELETE
- Bucket operations: create, list, delete, list-buckets
- Internal sync endpoint: `/internal/v1/sync/`
- 307 redirect of non-leaders to the leader

## Routing

```
GET  /healthz                  → HealthHandler (always 200/503, before cluster ready check)
GET  /                         → listBuckets
PUT  /{bucket}                 → createBucket
GET  /{bucket}?prefix=…        → listObjects
DELETE /{bucket}               → deleteBucket
PUT  /{bucket}/{key}           → put (+ replication if leader)
GET  /{bucket}/{key}           → get
HEAD /{bucket}/{key}           → head
DELETE /{bucket}/{key}         → delete (+ replication if leader)
GET  /internal/v1/sync/{p}     → SyncHandler (internal cluster endpoint)
```

## Startup modes

**Standalone** (`Q1_ETCD` absent):
```
Q1Server(engine, port)
```
All requests served locally, no replication.

**Cluster** (`Q1_ETCD` present):
```
Q1Server(engine, cluster, partitionRouter, replicator, port)
```
Startup sequence:
1. `EtcdCluster.start()` — lease + elections
2. `CatchupManager.catchUp(engine)` — sync lagging partitions
3. `Q1Server.start()` — open HTTP port

## Key files

| File | Role |
|---|---|
| `Q1Server.java` | Entry point, `main()`, construction based on env vars |
| `S3Router.java` | URL parsing, replica mode detection, non-leader redirect |
| `handler/ObjectHandler.java` | PUT/GET/HEAD/DELETE objects + replicator call |
| `handler/BucketHandler.java` | Bucket ops + `sendXml`, `sendError` helpers |
| `handler/SyncHandler.java` | Serves sync streams to followers |

## Gotchas

- `exchange.startBlocking()` required before `exchange.getInputStream()` (Undertow)
- `BucketHandler.sendError` must be `public` (called from `S3Router` in a different package)
- The `X-Q1-Replica-Write: true` header bypasses replication and routing
- Bucket writes (create/delete) are **not routed** by partition (no `leaderBaseUrl`)
  → buckets must be created on each node separately (not replicated yet)

## AWS SDK gotcha

The AWS SDK v2 uses `aws-chunked` encoding by default for PUTs. Our server reads the raw
body without decoding that format. Disable on the client side:
```java
S3Configuration.builder()
    .chunkedEncodingEnabled(false)
    ...
```

## Environment variables

See `q1-cluster/CLAUDE.md` or the corresponding section in the root CLAUDE.md.

## TODO

- [ ] SigV4 signature validation (currently any key/secret is accepted)
- [ ] Persistent Content-Type and Last-Modified (currently computed on the fly)
- [ ] Multipart upload (> 5 GB)
- [ ] `ListObjectsV2` pagination with `continuation-token`
- [ ] Rate limiting / circuit breaker on public endpoints
