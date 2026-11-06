package uk.gegc.quizmaker.features.attempt.infra.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptAccessRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.DENIED;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAttemptAccessRepository implements AttemptAccessRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAttemptAccessRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Evidence> lockEvidence(UUID attemptId, UUID trustedUserId) {
        // One indexed root read. No questions, answers, policy JSON, user secrets or mutable content.
        // FOR SHARE observes current committed facts even in an older caller REPEATABLE_READ snapshot.
        return jdbc.query("""
                SELECT BIN_TO_UUID(r.quiz_id) quiz_id, BIN_TO_UUID(r.owner_id) owner_id,
                  BIN_TO_UUID(r.id) revision_id, BIN_TO_UUID(f.id) form_id,
                  BIN_TO_UUID(p.id) policy_id, l.*
                FROM attempt_participant_subject u
                JOIN attempt_lifecycle_state l ON l.attempt_id=u.attempt_id
                JOIN attempt_trace_stream s ON s.attempt_id=u.attempt_id
                JOIN attempt_form f ON f.id=s.form_id
                JOIN attempt_policy_snapshot p ON p.id=s.policy_snapshot_id AND p.quiz_revision_id=f.quiz_revision_id
                JOIN quiz_revisions r ON r.id=f.quiz_revision_id
                WHERE u.attempt_id=UUID_TO_BIN(:attempt) AND u.user_id=UUID_TO_BIN(:user)
                  AND u.schema_version=1 AND CAST(u.subject_type AS BINARY)='AUTHENTICATED_USER'
                  AND s.schema_version=1 AND s.last_sequence>=2 AND f.schema_version=1
                  AND p.schema_version=1 AND p.resolver_version=1 AND p.canonical_version=1
                  AND CAST(p.hash_algorithm AS BINARY)='SHA-256' AND r.schema_version=1
                  AND l.lifecycle_version=1 AND l.row_version>=1 AND l.started_at IS NOT NULL
                FOR SHARE
                """, Map.of("attempt", attemptId.toString(), "user", trustedUserId.toString()), (row, index) -> {
            try {
                var state = new AttemptLifecycle.State(row.getInt("lifecycle_version"),
                        AttemptLifecycle.Execution.valueOf(row.getString("execution_state")),
                        AttemptLifecycle.AnswerCollection.valueOf(row.getString("answer_collection_state")));
                var lifecycle = new LifecycleState(attemptId, row.getLong("row_version"), state,
                        time(row, "created_at"), time(row, "updated_at"), time(row, "started_at"), time(row, "submitted_at"));
                return new Evidence(id(row, "quiz_id"), id(row, "owner_id"), id(row, "revision_id"),
                        id(row, "form_id"), id(row, "policy_id"), lifecycle);
            } catch (RuntimeException invalidEvidence) { throw new AttemptAccessException(DENIED); }
        }).stream().findFirst();
    }

    private static UUID id(ResultSet row, String column) throws SQLException {
        return UUID.fromString(row.getString(column));
    }

    private static Instant time(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
