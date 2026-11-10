package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.nio.ByteBuffer;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;
import uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptAllowanceRepository;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;
import static uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.require;

@Repository
public class JdbcAttemptAllowanceRepository implements AttemptAllowanceRepository {
    private final NamedParameterJdbcTemplate sql;
    private static final int READ_BATCH = 512; // SQL bind batch size, never a limit on attempts.
    private final com.fasterxml.jackson.databind.ObjectMapper metadata = new com.fasterxml.jackson.databind.ObjectMapper();
    private final CanonicalPolicyFormat format = new CanonicalPolicyFormat();
    public JdbcAttemptAllowanceRepository(NamedParameterJdbcTemplate sql) { this.sql = sql; }

    @Override public void lock(Scope scope, AttemptLimitPolicy policy) {
        transaction();
        var args = parameters(scope).addValue("maximum", policy.maximumAttempts())
                .addValue("seconds", policy.cooldown().getSeconds()).addValue("nanos", policy.cooldown().getNano());
        // Insert before lookup avoids absent-key shared gap locks. Only this subject/context contends.
        sql.update("""
                INSERT INTO attempt_allowance_scope
                  (user_id,revision_id,publication_version,schema_version,maximum_attempts,cooldown_seconds,cooldown_nanos,start_ids)
                VALUES (:user,:revision,:publication,1,:maximum,:seconds,:nanos,JSON_ARRAY())
                ON DUPLICATE KEY UPDATE user_id=attempt_allowance_scope.user_id
                """, args);
        var saved = sql.queryForObject("""
                SELECT * FROM attempt_allowance_scope
                WHERE user_id=:user AND revision_id=:revision AND publication_version=:publication FOR UPDATE
                """, args, (r, i) -> {
            require(r.getInt("schema_version") == 1, ELIGIBILITY_UNAVAILABLE);
            Long maximum = (Long) r.getObject("maximum_attempts");
            return new AttemptLimitPolicy(maximum == null ? AttemptLimitMode.UNLIMITED : AttemptLimitMode.FIXED_COUNT,
                    maximum, Duration.ofSeconds(r.getLong("cooldown_seconds"), r.getInt("cooldown_nanos")));
        });
        require(policy.equals(saved), ALLOWANCE_CONFLICT);
    }

    @Override public Facts readCurrent(Scope scope, AttemptLimitPolicy policy) {
        transaction();
        var args = parameters(scope);
        // A pre-extension target without durable publication-version evidence is unknown, not empty.
        var unknown = sql.query("""
                SELECT user_id FROM attempt_unclassified_publication
                WHERE user_id=:user AND revision_id=:revision FOR SHARE
                """, args, (r, i) -> r.getBytes(1));
        if (!unknown.isEmpty()) return new Facts(1, scope, Knowledge.LEGACY_UNCLASSIFIED, List.of(), List.of());
        // The directory supplies current point-read keys, including in an older RR snapshot.
        // It is an index, not completeness evidence: verifyComplete must also compare the
        // authoritative binding census before a denial or before the new start can commit.
        String directory = sql.queryForObject("SELECT CAST(start_ids AS CHAR) FROM attempt_allowance_scope "
                + "WHERE user_id=:user AND revision_id=:revision AND publication_version=:publication FOR UPDATE", args, String.class);
        List<byte[]> ids = new ArrayList<>();
        try {
            var tree = metadata.readTree(directory);
            require(tree != null && tree.isArray(), ELIGIBILITY_UNAVAILABLE);
            for (var id : tree) {
                require(id.isTextual(), ELIGIBILITY_UNAVAILABLE);
                ids.add(bytes(UUID.fromString(id.textValue())));
            }
        } catch (java.io.IOException | IllegalArgumentException invalid) { throw new AttemptStartException(ELIGIBILITY_UNAVAILABLE); }
        if (ids.isEmpty()) return new Facts(1, scope, Knowledge.COMPLETE, List.of(), List.of());
        var starts = new ArrayList<StartFact>();
        for (int offset = 0; offset < ids.size(); offset += READ_BATCH) {
            args.addValue("ids", ids.subList(offset, Math.min(offset + READ_BATCH, ids.size())));
            starts.addAll(sql.query("""
                SELECT b.attempt_id,b.schema_version binding_version,l.*,s.schema_version subject_version,
                       s.user_id,s.subject_type,t.schema_version trace_version,e.schema_version event_version,
                       e.to_execution,e.to_answers,p.canonical_json,p.policy_hash
                FROM attempt_start_binding b FORCE INDEX (PRIMARY)
                JOIN attempt_lifecycle_state l ON l.attempt_id=b.attempt_id
                LEFT JOIN attempt_participant_subject s ON s.attempt_id=b.attempt_id
                LEFT JOIN attempt_trace_stream t ON t.attempt_id=b.attempt_id
                LEFT JOIN attempt_trace_event e ON e.event_id=b.start_event_id AND e.attempt_id=b.attempt_id AND e.event_type='ATTEMPT_STARTED'
                LEFT JOIN attempt_policy_snapshot p ON p.id=t.policy_snapshot_id
                WHERE b.attempt_id IN (:ids) AND b.user_id=:user AND b.revision_id=:revision AND b.publication_version=:publication
                ORDER BY b.attempt_id FOR SHARE
                """, args, (r, i) -> {
            require(r.getInt("binding_version") == 1 && r.getInt("lifecycle_version") == 1
                    && r.getInt("subject_version") == 1 && r.getInt("trace_version") == 1
                    && r.getInt("event_version") == 1 && "AUTHENTICATED_USER".equals(r.getString("subject_type"))
                    && Arrays.equals(bytes(scope.subjectId()), r.getBytes("user_id"))
                    && "IN_PROGRESS".equals(r.getString("to_execution"))
                    && "NOT_STARTED".equals(r.getString("to_answers")), ELIGIBILITY_UNAVAILABLE);
            AttemptLimitPolicy captured;
            try { captured = format.decode(r.getString("canonical_json"), r.getString("policy_hash")).policy().attempts(); }
            catch (RuntimeException invalid) { throw new AttemptStartException(ELIGIBILITY_UNAVAILABLE); }
            require(policy.equals(captured), ALLOWANCE_CONFLICT);
            var id = uuid(r.getBytes("attempt_id"));
            var state = new AttemptLifecycle.State(1,
                    AttemptLifecycle.parseValue(1, AttemptLifecycle.Execution.class, r.getString("execution_state")),
                    AttemptLifecycle.parseValue(1, AttemptLifecycle.AnswerCollection.class, r.getString("answer_collection_state")));
            // Consumption is independent of grading/release; project only start/lifecycle authority.
            return StartFact.of(id, scope, time(r, "started_at"), time(r, "submitted_at"), state,
                    new ResultFact(1, null, ResultState.NONE));
        }));
        }
        require(starts.size() == ids.size(), ELIGIBILITY_UNAVAILABLE);
        return new Facts(1, scope, Knowledge.COMPLETE, starts, List.of());
    }

    @Override public void bind(Scope scope, UUID attemptId, UUID startEventId) {
        transaction();
        require(sql.update("""
                INSERT INTO attempt_start_binding (attempt_id,start_event_id,user_id,revision_id,publication_version,schema_version)
                SELECT :attempt,:event,:user,:revision,:publication,1 FROM attempt_trace_stream t
                JOIN attempt_participant_subject s ON s.attempt_id=t.attempt_id AND s.user_id=:user
                JOIN attempt_form f ON f.id=t.form_id AND f.quiz_revision_id=:revision
                JOIN attempt_lifecycle_state l ON l.attempt_id=t.attempt_id AND l.execution_state='IN_PROGRESS'
                JOIN attempt_trace_event e ON e.event_id=:event AND e.attempt_id=t.attempt_id AND e.event_type='ATTEMPT_STARTED'
                WHERE t.attempt_id=:attempt
                """, parameters(scope).addValue("attempt", bytes(attemptId)).addValue("event", bytes(startEventId))) == 1, ELIGIBILITY_UNAVAILABLE);
        require(sql.update("""
                UPDATE attempt_allowance_scope SET start_ids=JSON_ARRAY_APPEND(start_ids,'$',:id)
                WHERE user_id=:user AND revision_id=:revision AND publication_version=:publication
                """, parameters(scope).addValue("id", attemptId.toString())) == 1, ELIGIBILITY_UNAVAILABLE);
        verifyComplete(scope);
    }

    @Override public void verifyComplete(Scope scope) {
        transaction();
        var args = parameters(scope);
        Long indexed = sql.queryForObject("""
                SELECT JSON_LENGTH(start_ids) FROM attempt_allowance_scope
                WHERE user_id=:user AND revision_id=:revision AND publication_version=:publication FOR UPDATE
                """, args, Long.class);
        // A locking census sees committed/own writes even inside a stale RR snapshot. On the
        // allowed path it runs AFTER inserting our binding: no empty-range shared gap lock can
        // deadlock independent first inserts. After this check the command only completes its
        // receipt/commit; a rejecting check always rolls back. The scoped covering index avoids
        // scanning/locking other subjects' histories. No counter or guessed history is used.
        Long actual = sql.queryForObject("""
                SELECT COUNT(*) FROM attempt_start_binding FORCE INDEX (ix_start_binding_scope)
                WHERE user_id=:user AND revision_id=:revision AND publication_version=:publication FOR SHARE
                """, args, Long.class);
        require(indexed != null && indexed.equals(actual), ELIGIBILITY_UNAVAILABLE);
    }

    private static void transaction() {
        // No repository transaction interceptor: the owning service sanitizes SQL diagnostics
        // before the first transactional proxy can log them. Never allow autonomous JDBC writes.
        require(TransactionSynchronizationManager.isActualTransactionActive(), PERSISTENCE_UNAVAILABLE);
    }

    private static MapSqlParameterSource parameters(Scope scope) {
        return new MapSqlParameterSource("user", bytes(scope.subjectId())).addValue("revision", bytes(scope.contextId()))
                .addValue("publication", scope.contextVersion());
    }
    private static Instant time(ResultSet r, String column) throws SQLException {
        var value = r.getObject(column, LocalDateTime.class); return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] bytes) { var b = ByteBuffer.wrap(bytes); return new UUID(b.getLong(), b.getLong()); }
}
