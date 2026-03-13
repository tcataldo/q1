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

    public Q1StateMachine(StorageEngine engine) {
        this.engine = engine;
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage storage)
            throws IOException {
        super.initialize(server, groupId, storage);
        log.info("Q1StateMachine initialised for group {}", groupId);
    }

    /**
     * Called on every node (leader + followers) once a log entry has been
     * committed to a quorum. Applies the command to the local storage engine.
     */
    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        final LogEntryProto entry = trx.getLogEntry();
        updateLastAppliedTermIndex(entry.getTerm(), entry.getIndex());

        RatisCommand cmd = RatisCommand.decode(
                entry.getStateMachineLogEntry().getLogData());
        try {
            apply(cmd);
        } catch (IOException e) {
            log.error("Failed to apply command {} to StorageEngine", cmd.type(), e);
            // Surface the error to the client (leader path)
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.completedFuture(Message.EMPTY);
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
