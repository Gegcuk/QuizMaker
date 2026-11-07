package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.sql.Timestamp;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubmissionRepository;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAttemptSubmissionRepository implements AttemptSubmissionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAttemptSubmissionRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void insert(Submission v) {
        jdbc.update("""
                INSERT INTO attempt_submission (id,schema_version,attempt_id,form_id,policy_snapshot_id,
                    quiz_revision_id,command_id,state_version,accepted_count,unanswered_count,submitted_at)
                VALUES (UUID_TO_BIN(:id),1,UUID_TO_BIN(:attempt),UUID_TO_BIN(:form),UUID_TO_BIN(:policy),
                    UUID_TO_BIN(:revision),UUID_TO_BIN(:command),:version,:accepted,:unanswered,:at)
                """, parameters(v));
    }
    @Override public void enqueue(Submission v) {
        jdbc.update("""
                INSERT INTO attempt_finalization_intent (submission_id,attempt_id,schema_version,created_at)
                VALUES (UUID_TO_BIN(:id),UUID_TO_BIN(:attempt),1,:at)
                """, parameters(v));
    }
    private static MapSqlParameterSource parameters(Submission v) {
        return new MapSqlParameterSource("id", v.id().toString()).addValue("attempt", v.attemptId().toString())
                .addValue("form", v.formId().toString()).addValue("policy", v.policySnapshotId().toString())
                .addValue("revision", v.quizRevisionId().toString()).addValue("command", v.commandId().toString())
                .addValue("version", v.stateVersion()).addValue("accepted", v.acceptedCount())
                .addValue("unanswered", v.unansweredCount()).addValue("at", Timestamp.from(v.at()));
    }
}
