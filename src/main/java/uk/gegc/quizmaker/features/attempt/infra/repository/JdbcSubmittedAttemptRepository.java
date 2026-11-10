package uk.gegc.quizmaker.features.attempt.infra.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.application.SubmittedAttemptSource.Failure;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSubmittedAttemptRepository implements SubmittedAttemptRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcSubmittedAttemptRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Header> lock(UUID submissionId) {
        // Only the intent is exclusive: never upgrade shared immutable revision locks.
        return jdbc.query("""
                SELECT s.*, BIN_TO_UUID(s.id) sid, BIN_TO_UUID(s.attempt_id) aid,
                    BIN_TO_UUID(s.form_id) fid, BIN_TO_UUID(s.policy_snapshot_id) pid,
                    BIN_TO_UUID(s.quiz_revision_id) rid, BIN_TO_UUID(s.command_id) cid,
                    BIN_TO_UUID(r.owner_id) owner_id, BIN_TO_UUID(t.policy_snapshot_id) bound_policy,
                    BIN_TO_UUID(t.form_id) bound_form, i.schema_version intent_version,
                    t.schema_version stream_version, p.schema_version subject_version, p.subject_type,
                    e.schema_version expiry_version, e.effective_deadline expiry_deadline,
                    BIN_TO_UUID(e.command_id) expiry_command, e.state_version expiry_state_version
                FROM attempt_finalization_intent i JOIN attempt_submission s
                    ON s.id=i.submission_id AND s.attempt_id=i.attempt_id
                JOIN quiz_revisions r ON r.id=s.quiz_revision_id
                JOIN attempt_trace_stream t ON t.attempt_id=s.attempt_id
                JOIN attempt_participant_subject p ON p.attempt_id=s.attempt_id
                LEFT JOIN attempt_expiry_receipt e ON e.submission_id=s.id AND e.attempt_id=s.attempt_id
                WHERE i.submission_id=UUID_TO_BIN(:id) FOR UPDATE OF i FOR SHARE OF s
                """, Map.of("id", submissionId.toString()), (r, n) -> {
            if ("EXPIRY".equals(r.getString("completion_cause")) && (r.getInt("expiry_version") != 1
                    || r.getLong("expiry_state_version") != r.getLong("state_version")
                    || !r.getString("cid").equals(r.getString("expiry_command"))
                    || !r.getObject("submitted_at", LocalDateTime.class).equals(r.getObject("expiry_deadline", LocalDateTime.class))))
                throw new Failure(Failure.Reason.CORRUPT);
            return new Header(
                new AttemptSubmissionRepository.Submission(id(r,"sid"),id(r,"aid"),id(r,"fid"),id(r,"pid"),id(r,"rid"),
                        id(r,"cid"),r.getLong("state_version"),r.getInt("accepted_count"),r.getInt("unanswered_count"),
                        r.getObject("submitted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        AttemptSubmissionRepository.Cause.valueOf(r.getString("completion_cause"))), id(r,"owner_id"),
                id(r,"bound_policy"),id(r,"bound_form"),r.getInt("schema_version"),r.getInt("intent_version"),
                r.getInt("stream_version"),r.getInt("subject_version"),r.getString("subject_type"));
        }).stream().findFirst();
    }

    @Override public List<AcceptedResponseRepository.Value> responses(UUID attemptId) {
        return jdbc.query("""
                SELECT *, BIN_TO_UUID(id) rid, BIN_TO_UUID(form_id) fid, BIN_TO_UUID(form_item_id) iid,
                    BIN_TO_UUID(question_revision_id) qid, BIN_TO_UUID(command_id) cid
                FROM attempt_accepted_response WHERE attempt_id=UUID_TO_BIN(:id) ORDER BY form_item_id FOR SHARE
                """, Map.of("id", attemptId.toString()), (r, n) -> {
            if (r.getInt("schema_version") != 1) throw new Failure(Failure.Reason.UNSUPPORTED);
            return new AcceptedResponseRepository.Value(id(r,"rid"),attemptId,id(r,"fid"),id(r,"iid"),id(r,"qid"),
                    new ContractVersion(r.getString("response_contract_id"),r.getInt("response_contract_version")),
                    r.getString("normalized_response"),id(r,"cid"),r.getObject("accepted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        });
    }
    private static UUID id(ResultSet r, String column) throws SQLException { return UUID.fromString(r.getString(column)); }
}
