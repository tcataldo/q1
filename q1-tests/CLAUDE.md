# q1-tests — Integration Tests

Test-only module — no production code.

## Running tests

```bash
# Everything (unit + IT) from the root
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make

# Unit tests only (q1-core)
mvn test -pl q1-core

# S3CompatibilityIT only
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make -Dit.test="S3CompatibilityIT"

# ClusterIT only
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make -Dit.test="ClusterIT"
```

## Test counts

| Suite | Class | Tests |
|---|---|---|
| Unit | `SegmentTest` | 9 |
| Unit | `PartitionTest` | 13 |
| Unit | `StorageEngineSyncTest` | 5 |
| IT | `S3CompatibilityIT` | 12 |
| IT | `ClusterIT` | 4 |
| **Total** | | **43** |

## S3CompatibilityIT

- Starts a `Q1Server` **in-process** in standalone mode on port 19000
- Driven by the **AWS SDK v2** (`software.amazon.awssdk:s3`)
- `chunkedEncodingEnabled(false)` required (otherwise SDK sends `aws-chunked`)
- 404 tests produce stack traces in logs — this is normal (SDK throws exceptions)

Operations covered: createBucket (idempotency), PUT/GET object, GET binary 128 KiB,
HEAD exist/missing, DELETE, GET missing, listObjectsV2, keys with `/`, overwrite, empty object.

## ClusterIT

- 2 in-process nodes (ports 19200 and 19201)
- **etcd via Testcontainers**: `gcr.io/etcd-development/etcd:v3.5.17`
  - Command: `etcd --listen-client-urls=http://0.0.0.0:2379 --advertise-client-urls=http://0.0.0.0:2379`
  - (bitnami/etcd is not available in this environment)
- 4 partitions, RF=2, lease TTL=5s
- 4s wait for election stabilization before starting nodes

Scenarios covered:
- `replicationOnWrite` — PUT on one node, GET on the other → same data
- `nonLeaderRedirects` — PUT on non-leader → 307 with Location header
- `deleteReplicatedToFollower` — DELETE replicated, both nodes return 404
- `headOnBothNodes` — HEAD returns 200 on both nodes after PUT

**Important:** buckets must be created on **each node separately**
(bucket operations are not replicated). `createBucket()` runs on PORT0 and PORT1.

## TODO

- [ ] Compaction test: verify tombstones are properly cleaned up
- [ ] Elasticity test: add a 3rd node at runtime
- [ ] Fault tolerance test: kill the leader during replication
- [ ] Catchup test: a node that restarts behind and resynchronizes
- [ ] Benchmark: P50/P99 latency on PUT/GET for 1KB, 32KB, 128KB objects
