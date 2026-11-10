package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.attempt.application.AttemptExpiryService.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptExpiryRepository;

@Repository
public class JdbcAttemptExpiryRepository implements AttemptExpiryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAttemptExpiryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static void transaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new Failure(Failure.Reason.UNAVAILABLE);
    }
    @Override public List<Cursor> due(Instant now, int limit, Cursor after) {
        transaction();
        String keyset = after == null ? "" : " AND (effective_deadline>:afterTime OR (effective_deadline=:afterTime AND attempt_id>UUID_TO_BIN(:afterId)))";
        var p = new MapSqlParameterSource("now", Timestamp.from(now)).addValue("limit", limit);
        if (after != null) p.addValue("afterTime", Timestamp.from(after.deadline())).addValue("afterId", after.attemptId().toString());
        return jdbc.query("SELECT BIN_TO_UUID(attempt_id) aid,effective_deadline FROM attempt_expiry_work FORCE INDEX(ix_expiry_due)"
                + " WHERE effective_deadline<=:now" + keyset + " ORDER BY effective_deadline,attempt_id LIMIT :limit", p,
                (r,n) -> new Cursor(r.getObject("effective_deadline", LocalDateTime.class).toInstant(ZoneOffset.UTC), UUID.fromString(r.getString("aid"))));
    }
    @Override public Optional<UUID> lockSubject(UUID attemptId) {
        transaction();
        return jdbc.query("""
                SELECT BIN_TO_UUID(user_id) uid FROM attempt_participant_subject WHERE attempt_id=UUID_TO_BIN(:id)
                AND schema_version=1 AND CAST(subject_type AS BINARY)='AUTHENTICATED_USER' AND user_id IS NOT NULL FOR SHARE
                """, Map.of("id", attemptId.toString()), (r,n) -> UUID.fromString(r.getString("uid"))).stream().findFirst();
    }
    @Override public boolean claim(UUID attemptId) {
        transaction();
        // Same root and binding-before-root order as participant commands. No committed stale lease.
        return !jdbc.query("SELECT 1 FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(:id) FOR UPDATE SKIP LOCKED",
                Map.of("id", attemptId.toString()), (r,n) -> true).isEmpty();
    }
    @Override public Optional<Receipt> receipt(UUID attemptId) {
        transaction();
        // The command owns a fresh REQUIRES_NEW transaction and the lifecycle root before
        // its first consistent read. Immutable receipt evidence cannot change under that root.
        // Locking an absent receipt would gap-lock unrelated first inserts under RR.
        return jdbc.query("""
                SELECT e.*, BIN_TO_UUID(e.command_id) cid, BIN_TO_UUID(e.submission_id) sid,
                    s.completion_cause, s.state_version submission_version, s.submitted_at, BIN_TO_UUID(s.command_id) submission_command,
                    t.effective_deadline captured_deadline, t.schema_version timing_version
                FROM attempt_expiry_receipt e JOIN attempt_submission s ON s.id=e.submission_id AND s.attempt_id=e.attempt_id
                JOIN attempt_timing t ON t.attempt_id=e.attempt_id
                WHERE e.attempt_id=UUID_TO_BIN(:id)
                """, Map.of("id", attemptId.toString()), (r,n) -> {
            var deadline = r.getObject("effective_deadline", LocalDateTime.class).toInstant(ZoneOffset.UTC);
            if (r.getInt("timing_version") != 1 || !r.getString("cid").equals(r.getString("submission_command"))
                    || !deadline.equals(r.getObject("captured_deadline", LocalDateTime.class).toInstant(ZoneOffset.UTC))
                    || !"EXPIRY".equals(r.getString("completion_cause")) || r.getLong("state_version") != r.getLong("submission_version")
                    || !deadline.equals(r.getObject("submitted_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                throw new Failure(Failure.Reason.CORRUPT);
            return new Receipt(r.getInt("schema_version"), UUID.fromString(r.getString("cid")), deadline,
                    new CommandReceipt.Acknowledgement(1, attemptId, UUID.fromString(r.getString("sid")), r.getLong("state_version")));
        }).stream().findFirst();
    }
    @Override public boolean voluntaryTerminal(UUID attempt, long stateVersion, Instant completedAt) {
        transaction();
        return !jdbc.query("""
                SELECT 1 FROM attempt_submission WHERE attempt_id=UUID_TO_BIN(:id) AND schema_version=1
                  AND completion_cause='VOLUNTARY' AND state_version=:version AND submitted_at=:at FOR SHARE
                """, new MapSqlParameterSource("id",attempt.toString()).addValue("version",stateVersion)
                .addValue("at",Timestamp.from(completedAt)),(r,n)->true).isEmpty();
    }
    @Override public void record(UUID attempt, Instant deadline, Instant at, UUID command, CommandReceipt.Acknowledgement ack) {
        transaction();
        jdbc.update("""
                INSERT INTO attempt_expiry_receipt(attempt_id,schema_version,command_id,submission_id,state_version,effective_deadline,recorded_at)
                VALUES(UUID_TO_BIN(:id),1,UUID_TO_BIN(:command),UUID_TO_BIN(:submission),:version,:deadline,:at)
                """, new MapSqlParameterSource("id", attempt.toString()).addValue("command", command.toString())
                .addValue("submission", ack.resultId().toString()).addValue("version", ack.stateVersion())
                .addValue("deadline",Timestamp.from(deadline)).addValue("at",Timestamp.from(at)));
    }
    @Override public void removeWork(UUID attempt) {
        transaction();
        jdbc.update("DELETE FROM attempt_expiry_work WHERE attempt_id=UUID_TO_BIN(:id)", Map.of("id",attempt.toString()));
    }
}
