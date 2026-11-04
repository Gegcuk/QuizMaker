package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.domain.repository.CommandReceiptRepository;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcCommandReceiptRepository implements CommandReceiptRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcCommandReceiptRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Row reserve(Request request, UUID candidateId, Instant at) {
        var args = new MapSqlParameterSource("id", bytes(candidateId)).addValue("user", bytes(request.userId()))
                .addValue("key", request.key().digest()).addValue("context", bytes(request.contextId()))
                .addValue("operation", request.operation().name()).addValue("target", bytes(request.targetId()))
                .addValue("expected", request.expectedVersion()).addValue("input", request.input().digest())
                .addValue("at", LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        // Reserve before lookup: no shared absent-key gap lock; duplicate requests read the winner
        // under both supported isolation levels, including an existing stale RR snapshot.
        jdbc.update("""
                INSERT INTO attempt_command_receipt
                  (id,schema_version,user_id,key_hash,context_id,operation,target_id,expected_version,input_hash,status,recorded_at)
                VALUES (:id,1,:user,:key,:context,:operation,:target,:expected,:input,'IN_PROGRESS',:at)
                ON DUPLICATE KEY UPDATE id=attempt_command_receipt.id
                """, args);
        return jdbc.queryForObject("""
                SELECT * FROM attempt_command_receipt WHERE user_id=:user AND key_hash=:key FOR UPDATE
                """, args, (row, index) -> read(row));
    }

    @Override
    public void complete(UUID id, Acknowledgement acknowledgement) {
        int updated = jdbc.update("""
                UPDATE attempt_command_receipt SET status='COMMITTED', acknowledgement_version=1,
                  attempt_id=:attempt, result_id=:result, state_version=:state
                WHERE id=:id AND schema_version=1 AND status='IN_PROGRESS'
                """, new MapSqlParameterSource("id", bytes(id)).addValue("attempt", bytes(acknowledgement.attemptId()))
                .addValue("result", bytes(acknowledgement.resultId())).addValue("state", acknowledgement.stateVersion()));
        require(updated == 1, CORRUPT_RECEIPT);
    }

    @Override
    public Context context(UUID attemptId) {
        var rows = jdbc.query("""
                SELECT l.*, s.schema_version AS subject_version, s.subject_type, s.user_id
                FROM attempt_lifecycle_state l JOIN attempt_participant_subject s ON s.attempt_id=l.attempt_id
                WHERE l.attempt_id=:attempt FOR SHARE
                """, new MapSqlParameterSource("attempt", bytes(attemptId)), (row, index) -> {
            require(row.getInt("lifecycle_version") == 1 && row.getInt("subject_version") == 1, UNSUPPORTED_VERSION);
            try {
                require("AUTHENTICATED_USER".equals(row.getString("subject_type")), CORRUPT_RECEIPT);
                var state = new AttemptLifecycle.State(1,
                        AttemptLifecycle.parseValue(1, AttemptLifecycle.Execution.class, row.getString("execution_state")),
                        AttemptLifecycle.parseValue(1, AttemptLifecycle.AnswerCollection.class, row.getString("answer_collection_state")));
                var lifecycle = new LifecycleState(attemptId, row.getLong("row_version"), state,
                        time(row, "created_at"), time(row, "updated_at"), time(row, "started_at"), time(row, "submitted_at"));
                return new Context(uuid(row, "user_id"), lifecycle.rowVersion(), lifecycle.submittedAt());
            } catch (RuntimeException failure) { throw new ReceiptException(CORRUPT_RECEIPT); }
        });
        require(rows.size() == 1, CONTEXT_UNAVAILABLE);
        return rows.get(0);
    }

    @Override
    public int delete(UUID attemptId, int limit) {
        return jdbc.update("""
                DELETE FROM attempt_command_receipt WHERE attempt_id=:attempt AND status='COMMITTED'
                ORDER BY id LIMIT :limit
                """, new MapSqlParameterSource("attempt", bytes(attemptId)).addValue("limit", limit));
    }

    private static Row read(ResultSet row) throws SQLException {
        require(row.getInt("schema_version") == 1, UNSUPPORTED_VERSION);
        try {
            var request = new Request(1, uuid(row, "user_id"), uuid(row, "context_id"),
                    AttemptCommands.parseType(1, row.getString("operation")), uuid(row, "target_id"),
                    row.getLong("expected_version"), new Key(row.getString("key_hash")), new Fingerprint(row.getString("input_hash")));
            String status = row.getString("status");
            require("COMMITTED".equals(status) || "IN_PROGRESS".equals(status), CORRUPT_RECEIPT);
            boolean committed = "COMMITTED".equals(status);
            var ack = committed ? new Acknowledgement(row.getInt("acknowledgement_version"), uuid(row, "attempt_id"),
                    uuid(row, "result_id"), row.getLong("state_version")) : null;
            if (!committed) require(row.getObject("acknowledgement_version") == null && row.getObject("attempt_id") == null
                    && row.getObject("result_id") == null && row.getObject("state_version") == null, CORRUPT_RECEIPT);
            return new Row(uuid(row, "id"), request, uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.time(
                    time(row, "recorded_at")), committed, ack);
        } catch (RuntimeException failure) { throw new ReceiptException(CORRUPT_RECEIPT); }
    }

    private static Instant time(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
    private static UUID uuid(ResultSet row, String column) throws SQLException {
        byte[] bytes = row.getBytes(column);
        require(bytes != null && bytes.length == 16, CORRUPT_RECEIPT);
        var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
