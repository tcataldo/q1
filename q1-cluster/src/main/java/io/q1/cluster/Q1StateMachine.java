package io.q1.cluster;

import io.q1.core.StorageEngine;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Not implemented in this version. The Raft log is replayed from the start
 * on restart. Snapshot support is deferred (see RATIS.md).
 */
public final class Q1StateMachine extends BaseStateMachine {

    private static final Logger log = LoggerFactory.getLogger(Q1StateMachine.class);

    private final StorageEngine engine;

    /** Index at the time initialize() was called; used to detect catch-up replay. */
    private volatile long initAppliedIndex = -1;
    /** Flipped to true after the first applyTransaction() so we log catch-up once. */
    private volatile boolean catchupLogged = false;

    public Q1StateMachine(StorageEngine engine) {
        this.engine = engine;
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage storage)
            throws IOException {
        super.initialize(server, groupId, storage);
        initAppliedIndex = getLastAppliedTermIndex().getIndex();
        log.info("Q1StateMachine initialised for group {}, last applied index={}",
                groupId, initAppliedIndex);
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
     * leader's commit index after a restart.
     */
    @Override
    public void notifyTermIndexUpdated(long term, long index) {
        super.notifyTermIndexUpdated(term, index);
        logCaughtUpIfReady(term, index);
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
