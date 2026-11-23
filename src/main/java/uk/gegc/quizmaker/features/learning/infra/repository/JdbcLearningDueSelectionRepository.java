package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningDueSelection.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningDueSelectionRepository;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.learning.infra.repository.LearningScheduleEvidence.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningDueSelectionRepository implements LearningDueSelectionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat planFormat = new uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat();
    private final String manifest = new FsrsStateFormat().encodeManifest(FsrsManifest.approved());

    public JdbcLearningDueSelectionRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean sourceExists(Source source) {
        return safe(() -> !jdbc.query("""
                SELECT id FROM quiz_revisions WHERE id=:revision AND quiz_id=:quiz AND schema_version=:version
                """, parameters(source).addValue("version", source.revisionSchemaVersion()),
                (row, index) -> row.getBytes(1)).isEmpty());
    }

    @Override public List<Item> find(UUID learner, Source source, Instant now, Cursor after, int take) {
        var parameters = parameters(source).addValue("learner", bytes(learner)).addValue("manifest", manifest)
                .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).addValue("take", take);
        String position = "";
        if (after != null) {
            position = " AND (due_at>:afterTime OR (due_at=:afterTime AND question_revision_id>:afterQuestion))";
            parameters.addValue("afterTime", LocalDateTime.ofInstant(after.dueAt(), ZoneOffset.UTC))
                    .addValue("afterQuestion", bytes(after.questionRevisionId()));
        }
        // The slot's existing prefix index confines examination to this learner/source. Each JSON
        // value is bounded (16 KiB) and contains schedule metadata only. LIMIT bounds returned rows;
        // sorting may examine the source's active slots. This is not constant work per quiz size.
        // Unsupported headers are included before the cursor/time filter, never disguised as empty.
        String query = """
                WITH candidates AS (
                    SELECT s.question_revision_id,s.active_cycle_id,s.cycle_version,c.strategy_id,c.created_at,
                           f.sequence,f.state,e.transition,r.result_cycle_version AS initial_version,
                           r.result_state AS initial_state,
                           CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(f.state,'$.dueAt')),
                                'T',' '),'Z','') AS DATETIME(6)) AS due_at,
                           EXISTS(SELECT 1 FROM quiz_revision_items i
                                  WHERE i.quiz_revision_id=s.quiz_revision_id
                                  AND i.question_revision_id=s.question_revision_id) AS member
                    FROM learning_schedule_slot s
                    LEFT JOIN learning_schedule_cycle c ON c.id=s.active_cycle_id
                    LEFT JOIN learning_fsrs_state f ON f.cycle_id=c.id
                    LEFT JOIN learning_review_event e ON e.cycle_id=f.cycle_id AND e.sequence=f.sequence
                    LEFT JOIN learning_schedule_receipt r ON r.learner_id=c.learner_id AND r.command_id=c.create_command_id
                    WHERE s.learner_id=:learner AND s.quiz_id=:quiz AND s.quiz_revision_id=:revision
                ), checked AS (
                    SELECT candidates.*,
                           (NOT (strategy_id <=> 'fsrs/v1') OR state IS NULL OR due_at IS NULL OR member=0
                            OR NOT (JSON_EXTRACT(state,'$.version') <=> CAST('1' AS JSON))
                            OR NOT (JSON_EXTRACT(state,'$.manifest') <=> CAST(:manifest AS JSON))) AS invalid
                    FROM candidates
                )
                SELECT question_revision_id,active_cycle_id,cycle_version,strategy_id,created_at,
                       sequence,state,transition,initial_version,initial_state,due_at,member
                FROM checked
                WHERE invalid OR (due_at<=:now %s)
                ORDER BY invalid DESC,due_at,question_revision_id
                LIMIT :take
                """.formatted(position);
        return safe(() -> jdbc.query(query, parameters, (row, index) -> {
            UUID question = uuid(row, "question_revision_id");
            require(row.getBoolean("member"), CORRUPT);
            var saved = read(row, new Key(learner, source, question));
            var due = row.getObject("due_at", LocalDateTime.class);
            require(due != null && saved.state().dueAt().equals(due.toInstant(ZoneOffset.UTC)), CORRUPT);
            return new Item(question, saved.expected(), saved.state().dueAt());
        }));
    }

    @Override public List<Item> findMixed(UUID learner,Source source,Instant now,int take) {
        var parameters = parameters(source).addValue("learner", bytes(learner)).addValue("manifest", manifest)
                .addValue("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC)).addValue("take", take);
        // The slot's existing prefix index confines examination to this learner/source. Each JSON
        // value is bounded (32 KiB) and contains schedule metadata only. LIMIT bounds returned rows;
        // sorting may examine the source's active slots. This is not constant work per quiz size.
        // Unsupported headers are included before the time filter, never disguised as empty.
        String query = """
                WITH candidates AS (
                    SELECT s.question_revision_id,s.active_cycle_id,s.cycle_version,c.strategy_id,c.created_at,
                           CASE WHEN c.strategy_id='custom/v1' THEN cs.sequence ELSE f.sequence END AS sequence,
                           f.state,e.transition,r.result_cycle_version AS initial_version,r.result_state AS initial_state,
                           CASE WHEN c.strategy_id='custom/v1' THEN cs.due_at ELSE
                               CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(f.state,'$.dueAt')),
                                'T',' '),'Z','') AS DATETIME(6)) END AS due_at,
                           (cs.cycle_id IS NOT NULL AND cp.id IS NOT NULL AND cv.plan_id IS NOT NULL
                               AND cs.learner_id=s.learner_id AND c.cycle_version=s.cycle_version
                               AND cs.schedule_version>=cs.sequence) AS custom_valid,
                           cs.completed,cp.paused,cv.definition,
                           EXISTS(SELECT 1 FROM quiz_revision_items i
                                  WHERE i.quiz_revision_id=s.quiz_revision_id
                                  AND i.question_revision_id=s.question_revision_id) AS member
                    FROM learning_schedule_slot s
                    LEFT JOIN learning_schedule_cycle c ON c.id=s.active_cycle_id
                    LEFT JOIN learning_fsrs_state f ON f.cycle_id=c.id
                    LEFT JOIN learning_custom_state cs ON cs.cycle_id=c.id
                    LEFT JOIN learning_plan cp ON cp.id=cs.plan_id AND cp.learner_id=s.learner_id
                    LEFT JOIN learning_plan_version cv ON cv.plan_id=cs.plan_id AND cv.version=cs.plan_version
                    LEFT JOIN learning_review_event e ON e.cycle_id=f.cycle_id AND e.sequence=f.sequence
                    LEFT JOIN learning_schedule_receipt r ON r.learner_id=c.learner_id AND r.command_id=c.create_command_id
                    WHERE s.learner_id=:learner AND s.quiz_id=:quiz AND s.quiz_revision_id=:revision
                ), checked AS (
                    SELECT candidates.*,
                           (due_at IS NULL OR member=0 OR CASE WHEN strategy_id='custom/v1' THEN
                               NOT custom_valid OR NOT (JSON_EXTRACT(definition,'$.version') <=> CAST('1' AS JSON))
                            ELSE NOT (strategy_id <=> 'fsrs/v1') OR state IS NULL
                               OR NOT (JSON_EXTRACT(state,'$.version') <=> CAST('1' AS JSON))
                               OR NOT (JSON_EXTRACT(state,'$.manifest') <=> CAST(:manifest AS JSON)) END) AS invalid
                    FROM candidates
                )
                SELECT question_revision_id,active_cycle_id,cycle_version,strategy_id,created_at,
                       sequence,state,transition,initial_version,initial_state,due_at,member,invalid,definition
                FROM checked
                WHERE invalid OR (due_at<=:now AND (strategy_id='fsrs/v1' OR (completed=FALSE AND paused=FALSE)) )
                ORDER BY invalid DESC,due_at,question_revision_id
                LIMIT :take
                """;
        return safe(() -> jdbc.query(query, parameters, (row, index) -> {
            UUID question = uuid(row, "question_revision_id");
            require(row.getBoolean("member"), CORRUPT);
            if ("custom/v1".equals(row.getString("strategy_id"))) {
                planFormat.decode(
                        row.getString("definition"),uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.Definition.class);
                require(!row.getBoolean("invalid"),CORRUPT);
                return new Item(question,new uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Expected(
                        uuid(row,"active_cycle_id"),row.getLong("cycle_version"),row.getLong("sequence")),
                        row.getObject("due_at",LocalDateTime.class).toInstant(ZoneOffset.UTC));
            }
            var saved = read(row, new Key(learner, source, question));
            var due = row.getObject("due_at", LocalDateTime.class);
            require(due != null && saved.state().dueAt().equals(due.toInstant(ZoneOffset.UTC)), CORRUPT);
            return new Item(question, saved.expected(), saved.state().dueAt());
        }));
    }

    private static MapSqlParameterSource parameters(Source source) {
        return new MapSqlParameterSource("quiz", bytes(source.quizId())).addValue("revision", bytes(source.quizRevisionId()));
    }
    private static <T> T safe(Supplier<T> work) {
        try { return work.get(); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
}
