package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningHistorySelection.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import uk.gegc.quizmaker.features.result.application.ResultDisclosureService;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.*;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.sql.*;
import java.time.*;
import java.util.*;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.CORRUPT;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

/** Separate history ports share one metadata query. No source content, normalized answers or writes. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningHistorySources implements MistakeHistorySource, UnseenContentSource {
    private final NamedParameterJdbcTemplate jdbc;
    private final ResultDisclosureService disclosure;
    private final CanonicalPolicyFormat policies = new CanonicalPolicyFormat();

    public JdbcLearningHistorySources(NamedParameterJdbcTemplate jdbc, ResultDisclosureService disclosure) {
        this.jdbc = jdbc; this.disclosure = disclosure;
    }

    @Override public Batch mistakes(UUID learner, Source source, int afterPosition, int take) {
        return read(learner, source, afterPosition, take, true);
    }
    @Override public Batch unseen(UUID learner, Source source, int afterPosition, int take) {
        return read(learner, source, afterPosition, take, false);
    }

    private Batch read(UUID learner, Source source, int afterPosition, int take, boolean mistakes) {
        var p = new MapSqlParameterSource("learner", learner.toString()).addValue("quiz", source.quizId().toString())
                .addValue("revision", source.quizRevisionId().toString()).addValue("after", afterPosition)
                .addValue("take", take).addValue("mistakes", mistakes);
        try {
            // Keep the current result-read grant stable just as the source verifier keeps authorship stable.
            boolean resultAccess = !jdbc.query("""
                    SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id
                    JOIN permissions p ON p.permission_id=rp.permission_id
                    WHERE ur.user_id=UUID_TO_BIN(:learner) AND p.permission_name='ATTEMPT_READ'
                    LIMIT 1 FOR SHARE OF ur,rp,p
                    """, p, (r,n) -> true).isEmpty();
            p.addValue("resultAccess", resultAccess);
            // Compatibility, latest answers, absence checks and selection share ONE RC statement snapshot.
            // Window scans/sorts can grow with this source's history; LIMIT bounds returned metadata only.
            var rows = jdbc.query(QUERY, p, (row,n) -> {
                String classification = row.getString("availability");
                require(!"CORRUPT".equals(classification), CORRUPT);
                var availability = Availability.valueOf(classification);
                if (row.getString("source_item") == null) return new Batch(availability, List.of());
                require(availability == Availability.COMPLETE, CORRUPT);
                Evidence evidence = null;
                if (mistakes) {
                    var binding = new Binding(id(row,"attempt"), id(row,"form"), id(row,"policy"),
                            source.quizRevisionId(), row.getString("policy_hash"));
                    var saved = policies.decode(row.getString("canonical_json"), row.getString("policy_hash")).policy();
                    require(saved.content().quizRevisionId().equals(source.quizRevisionId()), CORRUPT);
                    var decisions = disclosure.evaluate(new Context(1,
                            new Audience(Access.VERIFIED, binding.attemptId(), learner, learner),
                            Policy.practice(binding, saved), new AttemptFacts(binding,
                                new AttemptLifecycle.State(1, AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE)),
                            new ResultFacts(1, binding, id(row,"result"), ResultState.FINAL),
                            new ReleaseFacts(1, binding, id(row,"result"), ReleaseState.RELEASED),
                            ReviewAccess.AVAILABLE, EnumSet.of(Category.CORRECTNESS)));
                    require(decisions.allows(Category.CORRECTNESS), CORRUPT);
                    evidence = new Evidence(binding.attemptId(), id(row,"result"), id(row,"response"),
                            row.getObject("accepted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
                }
                return new Batch(availability, List.of(new Item(id(row,"source_item"), id(row,"question"),
                        row.getInt("authored_position"), evidence)));
            });
            require(!rows.isEmpty(), CORRUPT);
            var availability = rows.get(0).availability();
            require(rows.stream().allMatch(r -> r.availability() == availability), CORRUPT);
            return new Batch(availability, rows.stream().flatMap(r -> r.items().stream()).toList());
        } catch (DataAccessException failure) {
            throw SafeDataAccess.sanitize(failure);
        } catch (uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException failure) {
            throw new LearningSessionException(CORRUPT);
        }
    }

    private static UUID id(ResultSet row, String column) throws SQLException {
        return UUID.fromString(row.getString(column));
    }

    private static final String QUERY = """
            WITH own_attempts AS (
                SELECT s.attempt_id, s.schema_version subject_version, s.subject_type,
                    f.id form_id, f.schema_version form_version, f.item_count form_item_count, t.policy_snapshot_id,
                    t.schema_version trace_version, l.lifecycle_version, l.execution_state,
                    l.answer_collection_state, r.id result_id, r.schema_version result_version,
                    r.scoring_contract, r.quiz_revision_id result_revision, r.form_id result_form,
                    r.policy_snapshot_id result_policy, r.policy_hash result_hash, r.created_at result_at,
                    r.submission_id result_submission, r.earned_points result_earned, r.possible_points result_possible,
                    (JSON_TYPE(JSON_EXTRACT(p.canonical_json,'$.policy.results.passThresholdBasisPoints'))='INTEGER'
                      AND r.pass_threshold=CAST(JSON_UNQUOTE(JSON_EXTRACT(p.canonical_json,'$.policy.results.passThresholdBasisPoints')) AS UNSIGNED)) result_threshold_valid,
                    p.schema_version policy_version, p.resolver_version, p.canonical_version, p.hash_algorithm,
                    p.quiz_revision_id policy_revision, p.policy_hash,
                    p.policy_hash=SHA2(p.canonical_json,256) policy_integrity,
                    (submission.schema_version=1 AND submission.form_id=f.id
                      AND submission.policy_snapshot_id=t.policy_snapshot_id AND submission.quiz_revision_id=f.quiz_revision_id
                      AND submission.state_version=l.row_version AND submission.submitted_at=l.submitted_at
                      AND submission.accepted_count=(SELECT COUNT(*) FROM attempt_accepted_response accepted_count
                        WHERE accepted_count.attempt_id=s.attempt_id)
                      AND submission.accepted_count+submission.unanswered_count=f.item_count
                      AND (submission.completion_cause='EXPIRY' OR submission.unanswered_count=0
                        OR JSON_CONTAINS(p.canonical_json,'false','$.policy.submission.requireAllAnswers'))
                      AND (submission.completion_cause<>'EXPIRY' OR EXISTS(SELECT 1 FROM attempt_expiry_receipt expiry
                        WHERE expiry.submission_id=submission.id AND expiry.attempt_id=s.attempt_id AND expiry.schema_version=1
                          AND expiry.state_version=submission.state_version AND expiry.command_id=submission.command_id
                          AND expiry.effective_deadline=submission.submitted_at))) submission_integrity,
                    CASE WHEN submission.completion_cause='EXPIRY' THEN l.updated_at ELSE submission.submitted_at END accepted_until,
                    (JSON_CONTAINS(p.canonical_json,'"CORRECTNESS"','$.policy.review.content')
                      AND (JSON_CONTAINS(p.canonical_json,'{"category":"CORRECTNESS","when":"AFTER_ACCEPTANCE"}','$.policy.disclosure.rules')
                        OR JSON_CONTAINS(p.canonical_json,'{"category":"CORRECTNESS","when":"AFTER_COMPLETION"}','$.policy.disclosure.rules'))) correctness_permitted
                FROM attempt_participant_subject s
                JOIN attempt_trace_stream t ON t.attempt_id=s.attempt_id
                JOIN attempt_form f ON f.id=t.form_id
                LEFT JOIN attempt_lifecycle_state l ON l.attempt_id=s.attempt_id
                LEFT JOIN attempt_submission submission ON submission.attempt_id=s.attempt_id
                LEFT JOIN attempt_policy_snapshot p ON p.id=t.policy_snapshot_id
                LEFT JOIN attempt_automatic_result r ON r.attempt_id=s.attempt_id
                WHERE s.user_id=UUID_TO_BIN(:learner) AND f.quiz_revision_id=UUID_TO_BIN(:revision)
            ), history AS (
                SELECT a.question_revision_id, a.id response_id, a.accepted_at, g.earned_points,
                    g.response_id graded_response_id, o.*,
                    ROW_NUMBER() OVER (PARTITION BY a.question_revision_id ORDER BY a.accepted_at DESC,a.id DESC) rn
                FROM own_attempts o JOIN attempt_accepted_response a ON a.attempt_id=o.attempt_id AND a.form_id=o.form_id
                LEFT JOIN attempt_automatic_grade g ON g.result_id=o.result_id AND g.response_id=a.id
                    AND g.question_revision_id=a.question_revision_id AND g.form_id=a.form_id AND g.form_item_id=a.form_item_id
                WHERE o.execution_state='SUBMITTED'
            ), latest AS (SELECT * FROM history WHERE rn=1), result_integrity AS (
                -- Validate whole referenced results before correctness, cursor or limit can hide corruption.
                -- Exact form membership/order and response identity match #603's replay metadata checks.
                SELECT roots.result_id, (COUNT(g.form_item_id)=roots.result_possible
                    AND roots.result_possible=roots.form_item_count AND roots.result_threshold_valid
                    AND (SELECT COUNT(*) FROM attempt_form_item f WHERE f.form_id=roots.form_id)=roots.result_possible
                    AND COALESCE(SUM(g.earned_points),0)=roots.result_earned
                    AND MIN(g.position)=0 AND MAX(g.position)=roots.result_possible-1
                    AND MIN((g.position=fi.position AND (g.response_id <=> accepted.id)
                        AND (accepted.id IS NULL OR (accepted.schema_version=1
                          AND CAST(accepted.response_contract_id AS BINARY)=CAST(b.response_contract_id AS BINARY)
                          AND accepted.response_contract_version=b.response_contract_version))
                        AND CAST(g.response_contract_id AS BINARY)=CAST(b.response_contract_id AS BINARY)
                        AND g.response_contract_version=b.response_contract_version
                        AND CAST(g.scoring_contract_id AS BINARY)=CAST(b.scoring_contract_id AS BINARY)
                        AND g.scoring_contract_version=b.scoring_contract_version) IS TRUE)=1
                    AND EXISTS(SELECT 1 FROM attempt_submission s WHERE s.id=roots.result_submission
                        AND s.schema_version=1 AND s.attempt_id=roots.attempt_id AND s.form_id=roots.form_id
                        AND s.policy_snapshot_id=roots.policy_snapshot_id AND s.quiz_revision_id=UUID_TO_BIN(:revision))
                    AND EXISTS(SELECT 1 FROM attempt_result_created e
                        JOIN attempt_trace_event t ON t.event_id=e.result_id
                        WHERE e.result_id=roots.result_id AND e.schema_version=1 AND e.created_at=roots.result_at
                          AND t.event_type='AUTOMATIC_GRADE_PRODUCED' AND t.actor_type='SYSTEM' AND t.reason_code='GRADED'
                          AND t.attempt_id=roots.attempt_id AND t.form_id=roots.form_id)) valid
                FROM (SELECT DISTINCT result_id,result_earned,result_possible,result_at,attempt_id,form_id,
                        result_submission,policy_snapshot_id,form_item_count,result_threshold_valid FROM latest
                    WHERE :mistakes AND :resultAccess AND result_id IS NOT NULL) roots
                LEFT JOIN attempt_automatic_grade g ON g.result_id=roots.result_id
                LEFT JOIN attempt_form_item fi ON fi.form_id=g.form_id AND fi.id=g.form_item_id
                LEFT JOIN attempt_accepted_response accepted ON accepted.attempt_id=roots.attempt_id
                    AND accepted.form_id=roots.form_id AND accepted.form_item_id=g.form_item_id
                LEFT JOIN question_revision_contract_bindings b ON b.question_revision_id=g.question_revision_id
                GROUP BY roots.result_id,roots.result_earned,roots.result_possible,roots.result_at,roots.attempt_id,roots.form_id,
                    roots.result_submission,roots.policy_snapshot_id,roots.form_item_count,roots.result_threshold_valid
            ), availability AS (
                SELECT CASE
                  WHEN EXISTS(SELECT 1 FROM attempts a JOIN answers answer_row ON answer_row.attempt_id=a.id
                      WHERE a.user_id=UUID_TO_BIN(:learner) AND a.quiz_id=UUID_TO_BIN(:quiz))
                    OR EXISTS(SELECT 1 FROM spaced_repetition_entry e JOIN question_revisions q ON q.question_id=e.question_id
                      JOIN quiz_revision_items i ON i.question_revision_id=q.id
                      WHERE e.user_id=UUID_TO_BIN(:learner) AND i.quiz_revision_id=UUID_TO_BIN(:revision))
                    OR EXISTS(SELECT 1 FROM quizzes q WHERE q.quiz_id=UUID_TO_BIN(:quiz) AND q.is_repetition_enabled=TRUE)
                    THEN 'LEGACY_UNKNOWN'
                  WHEN EXISTS(SELECT 1 FROM own_attempts o WHERE NOT (
                      o.subject_version=1 AND CAST(o.subject_type AS BINARY)='AUTHENTICATED_USER' AND o.form_version=1 AND o.trace_version=1
                      AND o.lifecycle_version=1 AND o.policy_version=1 AND o.resolver_version=1 AND o.canonical_version=1
                      AND CAST(o.hash_algorithm AS BINARY)='SHA-256' AND o.policy_revision=UUID_TO_BIN(:revision)
                      AND o.policy_integrity=TRUE
                      AND (o.result_id IS NULL OR (o.result_version=1 AND CAST(o.scoring_contract AS BINARY)='binary-result/v1'
                        AND o.result_revision=UUID_TO_BIN(:revision) AND o.result_form=o.form_id
                        AND o.result_policy=o.policy_snapshot_id AND CAST(o.result_hash AS BINARY)=CAST(o.policy_hash AS BINARY)))) IS TRUE)
                    THEN 'UNSUPPORTED'
                  WHEN :mistakes AND :resultAccess AND (
                      -- Validate every completed answer's ordering inputs, not just whichever timestamp wins.
                      EXISTS(SELECT 1 FROM history h WHERE h.submission_integrity IS NOT TRUE
                        OR h.accepted_until IS NULL OR h.accepted_at>h.accepted_until)
                      OR EXISTS(SELECT 1 FROM result_integrity WHERE valid IS NOT TRUE)
                      OR EXISTS(SELECT 1 FROM latest WHERE result_id IS NOT NULL AND graded_response_id IS NULL))
                    THEN 'CORRUPT'
                  WHEN (NOT :resultAccess AND EXISTS(SELECT 1 FROM own_attempts)) OR (:mistakes AND (
                      EXISTS(SELECT 1 FROM latest WHERE result_id IS NULL OR graded_response_id IS NULL)
                      OR EXISTS(SELECT 1 FROM latest h LEFT JOIN attempt_result_release published ON published.result_id=h.result_id
                          LEFT JOIN attempt_trace_event release_trace ON release_trace.event_id=published.id
                          WHERE published.id IS NULL OR published.schema_version<>1 OR published.released_at<h.result_at
                          OR NOT (release_trace.event_type='RESULT_RELEASED' AND release_trace.actor_type='SYSTEM'
                            AND release_trace.reason_code='RELEASED' AND release_trace.command_id=h.result_id
                            AND release_trace.attempt_id=h.attempt_id AND release_trace.form_id=h.form_id
                            AND release_trace.item_id IS NULL AND release_trace.from_execution IS NULL
                            AND release_trace.to_execution IS NULL) IS TRUE
                          OR EXISTS(SELECT 1 FROM attempt_scheduled_release scheduled WHERE scheduled.result_id=h.result_id)
                          OR h.correctness_permitted IS NOT TRUE)))
                    THEN 'SOURCE_UNAVAILABLE'
                  ELSE 'COMPLETE' END availability
            ), content_items AS (
                SELECT id,question_revision_id,authored_position,
                    ROW_NUMBER() OVER (PARTITION BY question_revision_id ORDER BY authored_position) occurrence
                FROM quiz_revision_items WHERE quiz_revision_id=UUID_TO_BIN(:revision)
            ), selected AS (
                SELECT i.*, h.attempt_id,h.form_id,h.policy_snapshot_id,h.policy_hash,
                    h.result_id,h.response_id,h.accepted_at
                FROM content_items i LEFT JOIN latest h ON h.question_revision_id=i.question_revision_id
                WHERE i.occurrence=1 AND i.authored_position>:after
                  AND ((:mistakes AND h.earned_points=0) OR (NOT :mistakes
                    AND NOT EXISTS(SELECT 1 FROM own_attempts o JOIN attempt_accepted_response a ON a.attempt_id=o.attempt_id
                        WHERE a.question_revision_id=i.question_revision_id)
                    AND NOT EXISTS(SELECT 1 FROM learning_session se JOIN learning_session_response response_row ON response_row.session_id=se.id
                        AND response_row.learner_id=se.learner_id
                        JOIN learning_form_item fi ON fi.form_id=response_row.form_id AND fi.position=response_row.item_position
                        WHERE se.learner_id=UUID_TO_BIN(:learner) AND se.quiz_id=UUID_TO_BIN(:quiz)
                        AND se.quiz_revision_id=UUID_TO_BIN(:revision) AND fi.question_revision_id=i.question_revision_id)))
                ORDER BY i.authored_position LIMIT :take
            )
            SELECT availability.availability, BIN_TO_UUID(selected.id) source_item,
                BIN_TO_UUID(selected.question_revision_id) question, selected.authored_position,
                BIN_TO_UUID(selected.attempt_id) attempt,BIN_TO_UUID(selected.form_id) form,
                BIN_TO_UUID(selected.policy_snapshot_id) policy,selected.policy_hash,
                CASE WHEN :mistakes THEN selected_policy.canonical_json END canonical_json,
                BIN_TO_UUID(selected.result_id) result,BIN_TO_UUID(selected.response_id) response,selected.accepted_at
            FROM availability LEFT JOIN selected ON availability.availability='COMPLETE'
            LEFT JOIN attempt_policy_snapshot selected_policy ON :mistakes AND selected_policy.id=selected.policy_snapshot_id
            ORDER BY selected.authored_position
            """;
}
