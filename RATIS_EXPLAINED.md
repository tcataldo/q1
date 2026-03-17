# Apache Ratis — Quick Reference for Q1

Distilled from reading Ratis source, decompiling jars, and debugging the Q1
multi-group implementation. Covers only the API surface we actually use.

---

## Core object model

```
RaftServer            one per JVM (one gRPC port)
  └── Division        one per RaftGroup hosted on this server
        ├── RaftLog   the append-only log for this group
        └── StateMachine  user code that applies committed entries

RaftGroup    = RaftGroupId (UUID) + List<RaftPeer>
RaftPeer     = RaftPeerId (string) + address ("host:port")
RaftClient   one per group; lives outside the server; used to send commands
```

A single `RaftServer` can host **many groups** simultaneously (multi-raft).
Each group elects its own leader independently.

---

## Server construction

```java
RaftProperties props = new RaftProperties();

// Transport port (shared across all groups on this server)
GrpcConfigKeys.Server.setPort(props, raftPort);

// Storage root — Ratis creates one sub-directory per group here
RaftServerConfigKeys.setStorageDir(props, List.of(new File(raftDataDir)));

// Size limits (increase if log entries can be large, e.g. 64 MB objects)
SizeInBytes maxEntry = SizeInBytes.valueOf("64MB");
GrpcConfigKeys.setMessageSizeMax(props, maxEntry);
RaftServerConfigKeys.Log.Appender.setBufferByteLimit(props, maxEntry);
RaftServerConfigKeys.Log.setWriteBufferSize(props, SizeInBytes.valueOf("128MB"));
RaftServerConfigKeys.Log.setSegmentSizeMax(props, SizeInBytes.valueOf("256MB"));

// Auto-snapshot every N committed entries
RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(props, true);
RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(props, 10_000L);

RaftServer server = RaftServer.newBuilder()
        .setServerId(RaftPeerId.valueOf("node1"))
        .setStateMachineRegistry(gid -> stateMachines.get(gid))   // multi-group
        // or: .setStateMachine(sm)                               // single-group
        .setProperties(props)
        .setOption(RaftStorage.StartupOption.RECOVER)  // or FORMAT on first start
        .build();
```

### `RECOVER` vs `FORMAT`

| Option | Behaviour |
|--------|-----------|
| `RECOVER` | Loads existing groups from `raftDataDir`, replays log after last snapshot. Safe for restart. |
| `FORMAT` | Wipes any existing state and starts fresh. Only for test teardown or explicit reset. |

Use `RECOVER` always in production. Ratis checks whether the directory exists and
bootstraps correctly either way — `RECOVER` on an empty dir behaves like `FORMAT`.

---

## Multi-group: `setStateMachineRegistry` vs `setStateMachine`

- **Single group**: `.setStateMachine(sm)` — one SM for the one group.
- **Multiple groups**: `.setStateMachineRegistry(gid -> sm)` — a function Ratis calls
  with the `RaftGroupId` when it initialises each group. Must return a distinct
  `StateMachine` instance per group (they are not thread-safe across groups).

---

## Adding groups after `server.start()`

```java
server.start();

// Groups recovered from disk are already active — DO NOT add them again.
Set<RaftGroupId> alreadyLoaded = new HashSet<>(server.getGroupIds());

ClientId adminId = ClientId.randomId();
long callId = 0;

for (RaftGroupId gid : myGroups) {
    if (alreadyLoaded.contains(gid)) continue;  // skip — already running

    RaftGroup group = RaftGroup.valueOf(gid, peers);   // see below
    GroupManagementRequest req = GroupManagementRequest.newAdd(
            adminId, selfPeerId, callId++, group);
    RaftClientReply reply = server.groupManagement(req);
    // reply.isSuccess() == false → inspect reply.getException()
}
```

**Pitfall**: calling `groupManagement(newAdd(...))` for a group that was already
recovered from disk throws `AlreadyExistsException` and the server logs an error.
Always check `server.getGroupIds()` first.

---

## Building a `RaftGroup`

```java
List<RaftPeer> peers = nodes.stream()
        .map(n -> RaftPeer.newBuilder()
                .setId(n.id())              // string → RaftPeerId
                .setAddress(n.host() + ":" + n.raftPort())  // gRPC address
                .build())
        .toList();

RaftGroup group = RaftGroup.valueOf(groupId, peers);
```

All members of the group must agree on the **same** `RaftGroupId` UUID and the
**same** peer list. Derive them deterministically (e.g. from a shared namespace UUID)
rather than negotiating them at runtime.

---

## Group ID derivation (Q1 pattern)

```java
UUID namespace = UUID.fromString(configuredGroupId);
long msb       = namespace.getMostSignificantBits();

RaftGroupId metaGroup       = RaftGroupId.valueOf(new UUID(msb, -1L));  // LSB = all 1s
RaftGroupId partitionGroup0 = RaftGroupId.valueOf(new UUID(msb, 0L));
RaftGroupId partitionGroup1 = RaftGroupId.valueOf(new UUID(msb, 1L));
// ...
```

All nodes use the same namespace → identical group IDs without coordination.
Test classes use different namespace UUIDs to isolate their Ratis background threads.

---

## Sending commands (`RaftClient`)

```java
// Build once per group, reuse for the lifetime of the cluster node.
RaftClient client = RaftClient.newBuilder()
        .setProperties(props)
        .setRaftGroup(group)
        .setClientId(ClientId.randomId())
        .build();

// Blocking send — returns after quorum commit
RaftClientReply reply = client.io().send(Message.valueOf(bytes));
if (!reply.isSuccess()) throw new IOException(reply.getException().toString());

// Non-blocking send — returns a CompletableFuture<RaftClientReply>
client.async().send(Message.valueOf(bytes));

// Close when done
client.close();
```

`Message.valueOf(ByteString)` / `Message.valueOf(byte[])` are the two common entry
points. The byte content is opaque to Ratis — encode your own command format in it.

---

## State machine implementation

Extend `BaseStateMachine`. Override three methods:

```java
public class MyStateMachine extends BaseStateMachine {

    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();

    @Override
    public StateMachineStorage getStateMachineStorage() { return storage; }

    // Called once when the server starts this group's Division.
    // Fast-forward to the latest snapshot so Ratis replays only newer entries.
    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage)
            throws IOException {
        super.initialize(server, groupId, raftStorage);
        storage.init(raftStorage);
        SingleFileSnapshotInfo snap = storage.getLatestSnapshot();
        if (snap != null) {
            TermIndex ti = snap.getTermIndex();
            updateLastAppliedTermIndex(ti.getTerm(), ti.getIndex());
        }
    }

    // Called on EVERY node once a log entry reaches quorum.
    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        LogEntryProto entry = trx.getLogEntry();
        updateLastAppliedTermIndex(entry.getTerm(), entry.getIndex());

        byte[] payload = entry.getStateMachineLogEntry().getLogData().toByteArray();
        // ... decode and apply ...

        return CompletableFuture.completedFuture(Message.EMPTY);
    }

    // Called by Ratis when auto-snapshot threshold is hit, or manually.
    @Override
    public long takeSnapshot() throws IOException {
        TermIndex last = getLastAppliedTermIndex();
        if (last == null || last.getIndex() < 0) return RaftLog.INVALID_LOG_INDEX;

        // Flush your durable state first — Ratis will purge log entries after this call.
        myDataStore.sync();

        // Write a marker file; term+index are encoded in the filename by Ratis:
        // e.g. "snapshot.3_10000"
        File f = storage.getSnapshotFile(last.getTerm(), last.getIndex());
        f.createNewFile();
        storage.updateLatestSnapshot(
                new SingleFileSnapshotInfo(new FileInfo(f.toPath(), null), last));

        return last.getIndex();
    }
}
```

### Snapshot file location

```
raftDataDir/
  <groupId-hex>/
    current/          ← active log segments
    sm/               ← SimpleStateMachineStorage root
      snapshot.<term>_<index>   ← empty marker file written by takeSnapshot()
```

Ratis reads `snapshot.<term>_<index>` on startup, calls `initialize()`, then
replays only log entries with index > snapshot index.

---

## Leadership detection

```java
// Is this server the leader for a given group?
boolean isLeader = server.getDivision(groupId).getInfo().isLeader();

// Who is the current leader (from a follower's perspective)?
RoleInfoProto role = server.getDivision(groupId).getInfo().getRoleInfoProto();
if (role.hasFollowerInfo()) {
    ServerRpcProto leaderRpc = role.getFollowerInfo().getLeaderInfo();
    String leaderId = leaderRpc.getId().getId().toStringUtf8(); // empty if unknown
}
```

`getDivision(gid)` throws `IOException` if the group is not hosted on this server.
Wrap in try-catch and return `false` / empty.

`getLeaderInfo()` can return an empty ID during the election window — always check
for empty string before trusting the value.

---

## `isClusterReady` pattern

Wait until **all managed groups** have a known leader before accepting client
requests. Checking only one group (e.g. a meta group) risks a race where writes
succeed on the leader but the local node hasn't applied them yet (the partition
group leader is different and has not yet been identified by this node).

```java
boolean isReady() {
    for (RaftGroupId gid : managedGroups) {
        try {
            RaftServer.Division div = server.getDivision(gid);
            if (div.getInfo().isLeader()) continue;           // this node IS the leader
            RoleInfoProto r = div.getInfo().getRoleInfoProto();
            if (!r.hasFollowerInfo()) return false;           // election in progress
            String id = r.getFollowerInfo().getLeaderInfo().getId().getId().toStringUtf8();
            if (id.isEmpty()) return false;                   // leader not yet known
        } catch (IOException e) {
            return false;
        }
    }
    return true;
}
```

---

## Key configuration knobs

| Key | Method | Notes |
|-----|--------|-------|
| gRPC port | `GrpcConfigKeys.Server.setPort(props, port)` | One port per server, shared by all groups |
| Storage dir | `RaftServerConfigKeys.setStorageDir(props, List.of(dir))` | Root; Ratis creates group sub-dirs |
| Max gRPC message | `GrpcConfigKeys.setMessageSizeMax(props, size)` | Must be ≥ largest log entry |
| Appender buffer | `RaftServerConfigKeys.Log.Appender.setBufferByteLimit(props, size)` | Per-follower send buffer |
| Write buffer | `RaftServerConfigKeys.Log.setWriteBufferSize(props, size)` | In-memory buffer before fsync |
| Log segment | `RaftServerConfigKeys.Log.setSegmentSizeMax(props, size)` | Roll new segment file at this size |
| Auto-snapshot | `RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(props, true)` | |
| Snapshot threshold | `RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(props, N)` | Every N committed entries |

`SizeInBytes.valueOf("64MB")` parses human-readable sizes.

---

## Common pitfalls

| Symptom | Cause | Fix |
|---------|-------|-----|
| `AlreadyExistsException` on `groupManagement(newAdd(...))` | Group was already recovered from disk (`RECOVER` mode) | Check `server.getGroupIds()` before calling `newAdd` |
| `IOException: group not found` from `getDivision(gid)` | This server is not a member of that group | Guard with try-catch; only query groups this node participates in |
| `reply.isSuccess() == false` with `NotLeaderException` | Client sent to a follower | Build the `RaftClient` with the full peer list; Ratis will automatically redirect to the leader after the first rejection — or proxy at the app level |
| Followers return stale data after a write | `isClusterReady()` checked only one group; client read hit the proxy node before it applied the commit | Check all managed groups in `isClusterReady()` |
| Large object write fails | Default gRPC message size is 4 MB | Raise `setMessageSizeMax`, `setBufferByteLimit`, and `setSegmentSizeMax` |
| Snapshot never triggers | `autoTriggerEnabled` defaults to `false` | Set it explicitly to `true` |

---

## Dependency versions (Q1)

```xml
<dependency>
    <groupId>org.apache.ratis</groupId>
    <artifactId>ratis-server</artifactId>
    <version>3.1.1</version>
</dependency>
<dependency>
    <groupId>org.apache.ratis</groupId>
    <artifactId>ratis-grpc</artifactId>
    <version>3.1.1</version>
</dependency>
```

The API is stable across 3.x. The main thing that changed between 2.x and 3.x is
the proto package (`org.apache.ratis.proto` vs `org.apache.ratis.shaded.proto`).
