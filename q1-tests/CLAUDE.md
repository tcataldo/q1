# q1-tests — Integration Tests

Test-only module — no production code.

## Running tests

```bash
# Everything (unit + IT) from the root
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make

# Unit tests only (q1-core)
mvn test -pl q1-core

# Single IT class
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make \
    -Dsurefire.failIfNoSpecifiedTests=false -Dtest=NONE \
    -Dit.test="RestartResilienceIT"
```

## Test counts

### Unit tests (run with `mvn test`)

| Module | Class | Tests |
|---|---|---|
| `q1-core` | `SegmentTest` | 9 |
| `q1-core` | `PartitionTest` | 24 |
| `q1-core` | `StorageEngineSyncTest` | 5 |
| `q1-erasure` | `ReedSolomonTest` | 21 |
| `q1-erasure` | `MatrixTest` | 19 |
| `q1-erasure` | `GaloisTest` | 14 |
| `q1-cluster` | `ErasureCoderTest` | 10 |
| **Total unit** | | **102** |

### Integration tests (run with `mvn verify`, Failsafe)

| Class | Ports (HTTP / Raft) | Raft group UUID | Tests |
|---|---|---|---|
| `S3CompatibilityIT` | 19000 / — | — (standalone) | 12 |
| `ClusterIT` | 19200–19201 / 16200–16201 | `51310003-…` | 5 |
| `ClusterReplicaIT` | 19210–19212 / 16210–16212 | `51310004-…` | 4 |
| `RestartResilienceIT` | 19440–19457 / 16440–16457 | `51310002-…` | 4 |
| `EcClusterIT` | 19300–19302 / 16300–16302 | `51310005-…` | 4 |
| `HealthzIT` | 19100 / — | — (standalone) | 5 |
| **Total IT** | | | **34** |

Each cluster IT uses a **unique Raft group UUID** (`ClusterConfig.raftGroupId`) so that
background Ratis gRPC threads from one test class do not interfere with another in the same JVM.

### BenchmarkIT (opt-in, disabled by default)

`BenchmarkIT` is **skipped by default** (`@EnabledIfSystemProperty(named="q1.benchmark", matches="true")`).
Starting 6 Ratis nodes leaves background threads that would interfere with the correctness ITs.

```bash
# Run the benchmark (replication + EC variants):
mvn verify -pl q1-core,q1-cluster,q1-api,q1-tests --also-make \
    -Dsurefire.failIfNoSpecifiedTests=false -Dtest=NONE \
    -Dit.test="BenchmarkIT" -Dq1.benchmark=true
```

| Variant | Ports (HTTP / Raft) | Raft group UUID |
|---|---|---|
| Replication (3-node) | 19500–19502 / 16500–16502 | `51310006-…` |
| EC k=2 m=1 (3-node) | 19510–19512 / 16510–16512 | `51310007-…` |

Workload: KEY_POOL=4000, ROUNDS=2, THREADS=8, email-size distribution (10%@1KB, 30%@8KB,
40%@32KB, 20%@128KB → avg 41KB). Each thread owns a non-overlapping key slice to avoid
cross-thread DELETE/GET races. Live working set ≈ 160 MB/node; total appended ≈ 820 MB/node.

## S3CompatibilityIT

- In-process standalone server on port 19000
- Driven by the **AWS SDK v2** (`software.amazon.awssdk:s3`)
- `chunkedEncodingEnabled(false)` required (SDK sends `aws-chunked` otherwise)
- 404 tests produce stack traces in logs — normal (SDK throws on 404)

Operations covered: createBucket (idempotency), PUT/GET object, GET binary 128 KiB,
HEAD exist/missing, DELETE, GET missing, listObjectsV2, keys with `/`, overwrite, empty object.

## ClusterIT (2-node Raft)

- 2 in-process nodes (ports 19200/19201, Raft 16200/16201)
- No Docker required
- Scenarios: `leaderIsElected`, `replicationOnWrite`, `writesToAnyNodeSucceed`,
  `deleteReplicatedToFollower`, `headOnBothNodes`

## ClusterReplicaIT (3-node Raft)

- 3 in-process nodes (ports 19210–19212, Raft 16210–16212)
- Scenarios: `exactlyOneLeaderElected`, `allNodesReceiveLiveWrites`,
  `deleteReplicatedToAllNodes`, `writesToAnyNodeSucceed`

## RestartResilienceIT (3-node Raft, restart scenarios)

Each test creates its own 3-node cluster in its own port range to isolate TIME_WAIT issues.
Uses a `ClusterNode` helper that persists `dataDir` (StorageEngine) and `raftDir` (Raft log)
across stop/start cycles.

- `followerRestartPreservesData` — write 10 keys, restart follower, verify all keys accessible
- `writesWhileFollowerDown` — stop follower, write 10 keys (quorum holds: 2/3), restart,
  verify follower replays all missed entries
- `leaderFailoverElectsNewLeader` — stop leader, verify new election among 2 survivors,
  write more keys, restart old leader, verify it catches up
- `snapshotRecoveryAfterRestart` — write 20 keys, force `takeSnapshot()` on follower,
  verify snapshot file exists, restart follower, verify all 20 keys accessible

## EcClusterIT (3-node EC 2+1)

- 3 in-process nodes (ports 19300–19302, Raft 16300–16302), EC k=2 m=1
- Scenarios: `ecPutGetRoundTrip`, `singleShardLossReconstruction`,
  `deleteRemovesAllShards`, `ecObjectInBucketListing`

## HealthzIT

- In-process standalone server on port 19100
- Tests: `healthzReturns200`, `healthzJsonStructure`, `healthzStandaloneMode`,
  `healthzContentType`, `healthzReturns503WhenNotReady`

## TODO

- [ ] EC fault tolerance IT: stop node mid-write, verify reconstruction + repair scanner
- [ ] Compaction IT: write many keys with deletes, trigger compaction, verify tombstone cleanup
