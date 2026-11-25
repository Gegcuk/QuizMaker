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
                    f.id form_id, f.schema_version form_version, t.policy_snapshot_id,
                    t.schema_version trace_version, l.lifecycle_version, l.execution_state,
                    l.answer_collection_state, r.id result_id, r.schema_version result_version,
                    r.scoring_contract, r.quiz_revision_id result_revision, r.form_id result_form,
                    r.policy_snapshot_id result_policy, r.policy_hash result_hash, r.created_at result_at,
                    r.earned_points result_earned, r.possible_points result_possible,
                    p.schema_version policy_version, p.resolver_version, p.canonical_version, p.hash_algorithm,
                    p.quiz_revision_id policy_revision, p.policy_hash,
                    p.policy_hash=SHA2(p.canonical_json,256) policy_integrity,
                    (JSON_CONTAINS(p.canonical_json,'"CORRECTNESS"','$.policy.review.content')
                      AND (JSON_CONTAINS(p.canonical_json,'{"category":"CORRECTNESS","when":"AFTER_ACCEPTANCE"}','$.policy.disclosure.rules')
                        OR JSON_CONTAINS(p.canonical_json,'{"category":"CORRECTNESS","when":"AFTER_COMPLETION"}','$.policy.disclosure.rules'))) correctness_permitted
                FROM attempt_participant_subject s
                JOIN attempt_trace_stream t ON t.attempt_id=s.attempt_id
                JOIN attempt_form f ON f.id=t.form_id
                LEFT JOIN attempt_lifecycle_state l ON l.attempt_id=s.attempt_id
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
                -- Unique result/position and result/item keys make count + endpoints prove contiguous positions.
                SELECT roots.result_id, (COUNT(g.form_item_id)=roots.result_possible
                    AND COALESCE(SUM(g.earned_points),0)=roots.result_earned
                    AND MIN(g.position)=0 AND MAX(g.position)=roots.result_possible-1) valid
                FROM (SELECT DISTINCT result_id,result_earned,result_possible FROM latest
                    WHERE :mistakes AND :resultAccess AND result_id IS NOT NULL) roots
                LEFT JOIN attempt_automatic_grade g ON g.result_id=roots.result_id
                GROUP BY roots.result_id,roots.result_earned,roots.result_possible
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
                      o.subject_version=1 AND o.subject_type='AUTHENTICATED_USER' AND o.form_version=1 AND o.trace_version=1
                      AND o.lifecycle_version=1 AND o.policy_version=1 AND o.resolver_version=1 AND o.canonical_version=1
                      AND o.hash_algorithm='SHA-256' AND o.policy_revision=UUID_TO_BIN(:revision)
                      AND o.policy_integrity=TRUE
                      AND (o.result_id IS NULL OR (o.result_version=1 AND o.scoring_contract='binary-result/v1'
                        AND o.result_revision=UUID_TO_BIN(:revision) AND o.result_form=o.form_id
                        AND o.result_policy=o.policy_snapshot_id AND o.result_hash=o.policy_hash))) IS TRUE)
                    THEN 'UNSUPPORTED'
                  WHEN :mistakes AND :resultAccess AND (
                      EXISTS(SELECT 1 FROM result_integrity WHERE valid IS NOT TRUE)
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
