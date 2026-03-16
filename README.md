# Q1 — S3-Compatible Object Store

**Q1** is a self-hosted, S3-compatible object store built for high-volume small-file workloads. It speaks the S3 API out of the box, runs as a single JAR, and scales to a replicated cluster using embedded Raft consensus — no external coordinator required.

> S3 is Amazon's, R2 is Cloudflare's, Q1 is ours.

[![Java 25](https://img.shields.io/badge/Java-25-blue)](#build--run)
[![Apache Ratis](https://img.shields.io/badge/Raft-Apache%20Ratis%203.1-orange)](#cluster-mode)
[![S3 Compatible](https://img.shields.io/badge/API-S3%20Compatible-yellow)](#s3-api-surface)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue)](#license)

---

## Table of Contents

- [Why Q1?](#why-q1)
- [Features](#features)
- [Quick Start](#quick-start)
- [Module Structure](#module-structure)
- [Architecture](#architecture)
  - [Storage Engine](#storage-engine)
  - [Cluster Mode](#cluster-mode)
  - [Erasure Coding](#erasure-coding)
- [S3 API Surface](#s3-api-surface)
- [Configuration](#configuration)
- [Build & Run](#build--run)
- [Running Tests](#running-tests)
- [Deployment](#deployment)
- [Roadmap](#roadmap)

---

## Why Q1?

Most object stores are either too heavy (full Ceph/MinIO deployments) or too simple (no replication, no erasure coding). Q1 hits the middle ground:

- **S3-wire-compatible** — drop in any AWS SDK v2 client without changes
- **Append-only segments** — no random writes; optimized for 128 KB objects
- **Embedded Raft** — replicated cluster from a single fat JAR, no ZooKeeper, no etcd
- **Erasure coding** — Reed-Solomon k+m, configurable per-deployment
- **Small operational surface** — one process, one data directory, environment-variable config

### Workload target

- 60 million email messages; ~80 % under 128 KB
- Efficient I/O optimized for the 128 KB sweet spot (not 4 KB block stores)
- Operations: `GET`, `PUT`, `HEAD`, `DELETE` on objects + bucket CRUD

---

## Features

| Feature | Status |
|---------|--------|
| S3-compatible HTTP API (path-style) | ✅ |
| `ListObjectsV2` with continuation tokens, delimiter, CommonPrefixes | ✅ |
| Append-only segment storage with CRC32 | ✅ |
| RocksDB persistent index (no rescan on startup) | ✅ |
| Two-phase crash-safe compaction | ✅ |
| Raft replication (Apache Ratis, embedded) | ✅ |
| Raft snapshots + bounded restart replay | ✅ |
| Erasure coding — Reed-Solomon k+m | ✅ |
| EC background repair scanner | ✅ |
| `/healthz` endpoint (JSON, 200/503) | ✅ |
| Virtual-thread HTTP server (Undertow) | ✅ |
| Ansible deployment playbooks | ✅ |
| AWS Signature V4 validation | 🔲 |
| Persistent ETag / Content-Type metadata | 🔲 |
| Multipart upload | 🔲 |
| Prometheus `/metrics` endpoint | 🔲 |

---

## Quick Start

```bash
# Build (skip tests)
mvn package -DskipTests

# Run standalone on port 9000
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -jar q1-api/target/q1-api-*.jar

# PUT an object
aws s3 --endpoint-url http://localhost:9000 \
    cp myfile.txt s3://mybucket/myfile.txt \
    --no-sign-request

# GET it back
aws s3 --endpoint-url http://localhost:9000 \
    cp s3://mybucket/myfile.txt - \
    --no-sign-request
```

No credentials are validated in the current build — any key/secret pair is accepted.

---

## Module Structure

```
q1/
├── q1-core/      Storage engine — segments, partitions, RocksDB index, compaction
├── q1-cluster/   Raft consensus (Apache Ratis) + request routing
├── q1-erasure/   Vendored Reed-Solomon codec (GF(2^8), no external dependency)
├── q1-api/       Undertow HTTP server, S3 router, EC object handler, repair scanner
├── q1-tests/     AWS SDK v2 compliance tests + cluster integration tests
└── q1-ansible/   Ansible playbooks for multi-node deployment
```

---

## Architecture

### Storage Engine

Each object is stored as a record inside an **append-only segment file**:

```
[4B] MAGIC    0x51310001
[1B] FLAGS    0x00=DATA | 0x01=TOMBSTONE
[2B] KEY_LEN  unsigned short
[8B] VAL_LEN  long  (0 for tombstones)
[4B] CRC32    covers FLAGS + key bytes + value bytes
[KEY_LEN B]   key  (UTF-8, internal format: bucket\x00objectKey)
[VAL_LEN B]   value bytes (absent for tombstones)
```

Segments roll over at **1 GiB**. Deletes write a tombstone; space is reclaimed by compaction (two-phase, crash-safe — see [COMPACTION.md](COMPACTION.md)).

The in-memory `SegmentIndex` maps each live key to `(segmentId, valueOffset, valueLength)`, backed by a **RocksDB** persistent index. On restart the index is ready immediately — no file scan required.

**Partitioning:** 16 partitions by default (configurable via `Q1_PARTITIONS`). Routing: `Math.abs(fullKey.hashCode()) % numPartitions`.

### Cluster Mode

Set `Q1_PEERS` to activate cluster mode. Q1 embeds **Apache Ratis** (Raft) — no external coordinator.

```
┌─────────────┐   write   ┌──────────────────┐
│  S3 Client  │ ────────▶ │  Non-leader node │
└─────────────┘           │  (HTTP proxy)    │
                          └────────┬─────────┘
                                   │ internal forward
                          ┌────────▼─────────┐   Raft log    ┌──────────────┐
                          │   Leader node    │ ──────────── ▶ │  Follower 1  │
                          └──────────────────┘               └──────────────┘
                                   │                         ┌──────────────┐
                                   └───────────────────────▶ │  Follower 2  │
                                                             └──────────────┘
```

- Non-leader nodes **proxy writes transparently** — the client sees `200`, never a redirect
- One global Raft group for all 16 partitions (no per-partition election)
- Raft log stored in `$Q1_DATA_DIR/raft/`; auto-snapshot every 10,000 entries
- Quorum = ⌊N/2⌋+1

See [RATIS.md](RATIS.md) and [REPLICATION.md](REPLICATION.md) for details.

### Erasure Coding

Activate with `Q1_EC_K` and `Q1_EC_M` (e.g. k=2, m=1 → tolerate 1 node loss without full replication overhead).

```
PUT object  →  encode into k+m shards  →  fan-out to k+m nodes
GET object  →  fetch available shards  →  Reed-Solomon decode
```

- Shards stored in an internal bucket (`__q1_ec_shards__`) under `{bucket}/{key}/{shardIdx:02d}`
- 8-byte `originalSize` header embedded in every shard (self-describing, no Raft metadata)
- **Background repair scanner**: detects missing shards via `HEAD`, reconstructs and re-uploads them
- Transparent fallback for objects stored before EC was enabled

See [ERASURECODING.md](ERASURECODING.md) for the full design.

---

## S3 API Surface

| Operation | Method | Path |
|-----------|--------|------|
| List buckets | `GET` | `/` |
| Create bucket | `PUT` | `/{bucket}` |
| Delete bucket | `DELETE` | `/{bucket}` |
| List objects v1/v2 | `GET` | `/{bucket}?list-type=2&...` |
| Put object | `PUT` | `/{bucket}/{key}` |
| Get object | `GET` | `/{bucket}/{key}` |
| Head object | `HEAD` | `/{bucket}/{key}` |
| Delete object | `DELETE` | `/{bucket}/{key}` |
| Health check | `GET` | `/healthz` |

**SDK note:** disable chunked encoding in your client config to avoid raw chunk headers:

```java
S3Configuration.builder().chunkedEncodingEnabled(false).build()
```

---

## Configuration

All configuration is via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `Q1_NODE_ID` | `node-{random8}` | Unique node name (must match a peer ID in `Q1_PEERS`) |
| `Q1_HOST` | `localhost` | Advertised HTTP hostname / IP |
| `Q1_PORT` | `9000` | HTTP listen port |
| `Q1_DATA_DIR` | `q1-data` | Data directory |
| `Q1_PEERS` | _(empty = standalone)_ | `id\|host\|httpPort\|raftPort` per node, comma-separated |
| `Q1_RAFT_PORT` | `6000` | Raft gRPC port (inter-node) |
| `Q1_PARTITIONS` | `16` | Number of partitions |
| `Q1_EC_K` | `0` _(disabled)_ | Erasure coding — data shards |
| `Q1_EC_M` | `0` _(disabled)_ | Erasure coding — parity shards |
| `Q1_REPAIR_INTERVAL_S` | `60` | EC repair scanner interval (seconds) |
| `Q1_REPAIR_BATCH_SIZE` | `200` | EC repair scanner batch size |

**Data directory layout:**

```
dataDir/
  buckets.properties       # bucket registry (creation timestamps)
  raft/                    # Raft log and snapshots
  p00/
    segment-0000000001.q1
    segment-0000000002.q1
    keyindex/              # RocksDB persistent index
  p01/ … p15/
```

---

## Build & Run

**Requirements:** Java 25, Maven 3.9+

```bash
# Build all modules (skip tests)
mvn package -DskipTests

# Standalone server (single node)
./launch.sh
# or
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -jar q1-api/target/q1-api-*.jar

# 3-node local cluster (separate data directories, ports 9000/9001/9002)
./launch-cluster.sh
```

---

## Running Tests

```bash
# All tests (unit + S3 compliance + cluster integration)
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make

# Unit tests only (fast)
mvn test -pl q1-core

# S3 compliance tests (standalone, in-process)
mvn verify -pl q1-tests

# Cluster tests (Ratis in-process, no Docker)
mvn verify -pl q1-tests -Pcluster-tests

# Single integration test
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make \
    -Dtest=NONE -Dit.test="RestartResilienceIT"

# Coverage report (JaCoCo)
mvn verify -pl q1-tests --also-make
# Report at: q1-tests/target/site/jacoco-aggregate/index.html
```

### Test suite

| Test class | Coverage |
|------------|---------|
| `SegmentTest` | Append/read round-trip, tombstone, `scanStream`, truncation safety |
| `PartitionTest` | CRUD, overwrite, prefix listing, sync stream round-trip |
| `StorageEngineSyncTest` | Full `openSyncStream → applySyncStream` across two engines |
| `S3CompatibilityIT` | AWS SDK v2 driving all supported ops against an in-process server |
| `ClusterIT` | 2-node Raft: replication, transparent proxy, delete propagation |
| `ClusterReplicaIT` | 3-node Raft: single leader, writes to any node |
| `RestartResilienceIT` | Follower restart, writes-while-down catch-up, leader failover |
| `EcClusterIT` | 3-node EC(2+1): encode/decode, single-shard loss reconstruction |
| `HealthzIT` | `/healthz` in standalone and cluster mode |

---

## Deployment

Ansible playbooks are included under `q1-ansible/`:

```bash
# Deploy Q1 to all nodes defined in inventory
ansible-playbook -i inventory q1-ansible/site.yml

# Purge data and redeploy
ansible-playbook -i inventory q1-ansible/purge-and-deploy.yml
```

---

## Roadmap

- [ ] Segment merging — compact multiple small/sparse segments into one to reduce file-handle pressure and improve scan locality
- [ ] Storage tiering — during compaction/merge, migrate cold segments (older writes, rarely read) to a slower storage backend; objects are immutable by design (keys are content-addressed hashes), so cold data is stable and safe to tier
- [ ] Prometheus `/metrics` endpoint
- [ ] Multipart upload (objects > 5 GB)
- [ ] AWS Signature V4 validation
- [ ] Dynamic cluster membership (Ratis `setConfiguration`)
- [ ] Bucket replication (create/delete propagated via Raft)
- [ ] EC re-encoding on cluster topology change

---

## License

[GNU Affero General Public License v3.0](https://www.gnu.org/licenses/agpl-3.0.html) (AGPL-3.0)
