package io.q1.cluster;

import io.q1.core.StorageEngine;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.StateMachineStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Raft state machine for Q1.
 *
 * <p>Each committed log entry carries a {@link RatisCommand}. The state machine
 * decodes it and applies the mutation to the local {@link StorageEngine}.
 * Because Raft guarantees that every node applies entries in the same order,
 * all nodes converge to the same storage state without any additional
 * fan-out or catchup logic.
 *
 * <h3>Consistency model</h3>
 * Writes (PUT, DELETE, bucket ops) go through the Raft log — they are
 * linearisable. Reads are served directly from the local engine (eventual
 * consistency), same as before the Ratis migration.
 *
 * <h3>Snapshots</h3>
 * The {@link StorageEngine} state (segments + RocksDB index) is already
 * durably persisted on disk independently of Raft. A snapshot therefore
 * only records the {@code (term, index)} boundary at which the engine is
 * consistent: term+index are encoded in the snapshot filename by
 * {@link SimpleStateMachineStorage} and the file content is empty.
 *
 * <p>Before writing the snapshot, {@link StorageEngine#sync()} is called to
 * guarantee that all applied entries are flushed to disk. Ratis will then
 * purge log entries up to the snapshot index, so without that fsync a crash
 * between snapshot and flush could cause data loss.
 *
 * <p>Auto-triggering is configured in {@link RatisCluster} via
 * {@code RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold}.
 */
public final class Q1StateMachine extends BaseStateMachine {

    private static final Logger log = LoggerFactory.getLogger(Q1StateMachine.class);

    private final StorageEngine           engine;
    private final SimpleStateMachineStorage snapshotStorage = new SimpleStateMachineStorage();

    /** Index at the time initialize() was called; used to detect catch-up replay. */
    private volatile long    initAppliedIndex = -1;
    /** Flipped to true after the first applyTransaction() so we log catch-up once. */
    private volatile boolean catchupLogged    = false;
    /** Last Raft term we saw — used to detect new elections. */
    private volatile long    lastSeenTerm     = -1;

    public Q1StateMachine(StorageEngine engine) {
        this.engine = engine;
    }

    @Override
    public StateMachineStorage getStateMachineStorage() {
        return snapshotStorage;
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage storage)
            throws IOException {
        super.initialize(server, groupId, storage);
        snapshotStorage.init(storage);

        // If a snapshot exists, fast-forward to its (term, index).
        // Ratis will replay only the log entries that follow it.
        SingleFileSnapshotInfo snapshot = snapshotStorage.getLatestSnapshot();
        if (snapshot != null) {
            TermIndex ti = snapshot.getTermIndex();
            updateLastAppliedTermIndex(ti.getTerm(), ti.getIndex());
            log.info("Loaded snapshot at (t:{}, i:{}) — replaying only subsequent log entries",
                    ti.getTerm(), ti.getIndex());
        }

        initAppliedIndex = getLastAppliedTermIndex().getIndex();
        log.info("Q1StateMachine initialised for group {}, last applied index={}",
                groupId, initAppliedIndex);
    }

    /**
     * Take a snapshot of the current state machine state.
     *
     * <p>The actual data is already on disk in the StorageEngine, so the
     * snapshot file is an empty marker — term+index are encoded in the filename
     * by {@link SimpleStateMachineStorage} (e.g. {@code snapshot.3_10000}).
     *
     * <p>Returns {@link RaftLog#INVALID_LOG_INDEX} if there is nothing to snapshot yet.
     */
    @Override
    public long takeSnapshot() throws IOException {
        TermIndex last = getLastAppliedTermIndex();
        if (last == null || last.getIndex() < 0) {
            return RaftLog.INVALID_LOG_INDEX;
        }

        // Ensure all StorageEngine writes are on disk before Ratis purges the log.
        engine.sync();

        // Write an empty marker file — state is already in StorageEngine on disk.
        // SimpleStateMachineStorage encodes term+index in the filename itself.
        File snapshotFile = snapshotStorage.getSnapshotFile(last.getTerm(), last.getIndex());
        if (!snapshotFile.createNewFile() && !snapshotFile.exists()) {
            throw new IOException("Could not create snapshot file: " + snapshotFile);
        }

        snapshotStorage.updateLatestSnapshot(
                new SingleFileSnapshotInfo(new FileInfo(snapshotFile.toPath(), null), last));

        log.info("Snapshot taken at (t:{}, i:{}) — Raft log entries up to {} eligible for purge",
                last.getTerm(), last.getIndex(), last.getIndex());
        return last.getIndex();
    }

    /** Called when this node wins an election and its state machine is fully up to date. */
    @Override
    public void notifyLeaderReady() {
        log.info("Q1StateMachine ready as LEADER at index={}", getLastAppliedTermIndex().getIndex());
    }

    /**
     * Called on every node (leader + followers) once a log entry has been
     * committed to a quorum. Applies the command to the local storage engine.
     */
    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        final LogEntryProto entry = trx.getLogEntry();
        final long term  = entry.getTerm();
        final long index = entry.getIndex();
        updateLastAppliedTermIndex(term, index);

        RatisCommand cmd = RatisCommand.decode(
                entry.getStateMachineLogEntry().getLogData());
        try {
            apply(cmd);
        } catch (IOException e) {
            log.error("Failed to apply command {} to StorageEngine", cmd.type(), e);
            // Surface the error to the client (leader path)
            return CompletableFuture.failedFuture(e);
        }

        logCaughtUpIfReady(term, index);
        return CompletableFuture.completedFuture(Message.EMPTY);
    }

    /**
     * Called for non-state-machine entries (NOOPs, configuration changes).
     * These are committed by Raft but do not go through applyTransaction.
     * Overriding here lets us detect when a follower has caught up to the
     * leader's commit index after a restart, and when a new election occurs.
     */
    @Override
    public void notifyTermIndexUpdated(long term, long index) {
        super.notifyTermIndexUpdated(term, index);
        if (term > lastSeenTerm) {
            lastSeenTerm = term;
            logNewLeader(term);
        }
        logCaughtUpIfReady(term, index);
    }

    /**
     * Logs which leader this follower is now following after a new election.
     * Skipped if this node itself won the election (handled by notifyLeaderReady).
     */
    private void logNewLeader(long term) {
        RaftServer srv = getServer().getNow(null);
        if (srv == null) return;
        try {
            var division = srv.getDivision(getGroupId());
            if (division.getInfo().isLeader()) return; // handled by notifyLeaderReady()
            var roleInfo = division.getInfo().getRoleInfoProto();
            if (!roleInfo.hasFollowerInfo()) return;
            String leaderId = roleInfo.getFollowerInfo().getLeaderInfo()
                    .getId().getId().toStringUtf8();
            if (!leaderId.isEmpty()) {
                log.info("Following leader {} at term {}", leaderId, term);
            }
        } catch (IOException e) {
            // server not fully initialised yet
        }
    }

    /**
     * Logs once when the applied index reaches the current commit index,
     * signalling that this node has caught up and is fully in sync.
     */
    private void logCaughtUpIfReady(long term, long index) {
        if (catchupLogged) return;
        RaftServer srv = getServer().getNow(null);
        if (srv == null) return;
        try {
            long commitIndex = srv.getDivision(getGroupId())
                    .getRaftLog().getLastCommittedIndex();
            if (index >= commitIndex) {
                catchupLogged = true;
                log.info("State machine in sync at (t:{}, i:{}) — node is ready", term, index);
            }
        } catch (IOException e) {
            // server not fully started yet, will retry on next entry
        }
    }

    private void apply(RatisCommand cmd) throws IOException {
        switch (cmd.type()) {
            case PUT           -> engine.put(cmd.bucket(), cmd.key(), cmd.value());
            case DELETE        -> engine.delete(cmd.bucket(), cmd.key());
            case CREATE_BUCKET -> engine.createBucket(cmd.bucket());
            case DELETE_BUCKET -> engine.deleteBucket(cmd.bucket());
        }
        log.debug("Applied {} bucket={} key={}", cmd.type(), cmd.bucket(), cmd.key());
    }

    @Override
    public TermIndex getLastAppliedTermIndex() {
        return super.getLastAppliedTermIndex();
    }
}
