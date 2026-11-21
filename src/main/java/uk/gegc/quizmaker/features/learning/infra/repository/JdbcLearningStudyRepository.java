package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.learning.application.LearningStudyService.EnrolledItem;
import uk.gegc.quizmaker.features.learning.application.LearningSessionCompletionVerifier;
import uk.gegc.quizmaker.features.learning.domain.model.LearningReview.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Expected;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningStudyRepository;
import uk.gegc.quizmaker.shared.exception.SafeDataAccess;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.CORRUPT;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.STALE_REVIEW;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningStudyRepository implements LearningStudyRepository, LearningSessionCompletionVerifier {
    private final JdbcTemplate jdbc;
    public JdbcLearningStudyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean prepared(UUID revisionId) {
        return safe(() -> !jdbc.queryForList("SELECT 1 FROM learning_prepared_publication WHERE revision_id=UUID_TO_BIN(?)",
                Integer.class, revisionId.toString()).isEmpty());
    }

    @Override public void recordPreparation(UUID revisionId) {
        safe(() -> jdbc.update("INSERT INTO learning_prepared_publication(revision_id) VALUES(UUID_TO_BIN(?))", revisionId.toString()));
    }

    @Override public boolean shuffleQuestions(UUID quizId) {
        return safe(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT shuffle_learning_questions FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)",
                Boolean.class, quizId.toString())));
    }

    @Override public Optional<Start> start(UUID sessionId) {
        return start(sessionId, false);
    }

    @Override public Optional<Start> lockStart(UUID sessionId) {
        return start(sessionId, true);
    }

    private Optional<Start> start(UUID sessionId, boolean lock) {
        return safe(() -> jdbc.query("""
                SELECT requested_size, BIN_TO_UUID(form_id), review_mode, BIN_TO_UUID(selected_question_id), reviewed_items
                FROM learning_session_start
                WHERE session_id=UUID_TO_BIN(?)
                """ + (lock ? " FOR UPDATE" : ""), (r,n) -> new Start(r.getInt(1), uuid(r.getString(2)),
                        Mode.valueOf(r.getString(3)), uuid(r.getString(4)), r.getInt(5)), sessionId.toString())
                .stream().findFirst());
    }

    @Override public void recordStart(UUID sessionId, UUID formId, int limit, boolean shuffled, Mode mode, UUID selectedQuestion) {
        safe(() -> jdbc.update("""
                INSERT INTO learning_session_start(session_id,form_id,requested_size,shuffled,review_mode,selected_question_id)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,UUID_TO_BIN(?))
                """, sessionId.toString(), formId.toString(), limit, shuffled, mode.name(),
                selectedQuestion == null ? null : selectedQuestion.toString()));
    }

    @Override public Optional<Enrollment> enrollment(UUID learner, UUID command) {
        return safe(() -> jdbc.query("""
                SELECT BIN_TO_UUID(quiz_revision_id),item_count FROM learning_enrollment
                WHERE learner_id=UUID_TO_BIN(?) AND command_id=UUID_TO_BIN(?)
                """, (r,n) -> new Enrollment(UUID.fromString(r.getString(1)),r.getInt(2)),
                learner.toString(),command.toString()).stream().findFirst());
    }

    @Override public List<EnrolledItem> enrolledItems(UUID learner, UUID command) {
        return safe(() -> jdbc.query("""
                SELECT i.position,BIN_TO_UUID(i.question_revision_id),BIN_TO_UUID(i.cycle_id),i.cycle_version
                FROM learning_enrollment_item i
                JOIN learning_enrollment e ON e.learner_id=i.learner_id AND e.command_id=i.command_id
                JOIN learning_schedule_cycle c ON c.id=i.cycle_id AND c.learner_id=i.learner_id
                    AND c.question_revision_id=i.question_revision_id AND c.quiz_revision_id=e.quiz_revision_id
                    AND c.cycle_version=i.cycle_version
                WHERE i.learner_id=UUID_TO_BIN(?) AND i.command_id=UUID_TO_BIN(?) ORDER BY i.position
                """, (r,n) -> {
            require(n == r.getInt(1), CORRUPT);
            return new EnrolledItem(UUID.fromString(r.getString(2)), UUID.fromString(r.getString(3)), r.getLong(4));
        }, learner.toString(), command.toString()));
    }

    @Override public void recordEnrollment(UUID learner, UUID command, UUID revision, List<EnrolledItem> items) {
        safe(() -> jdbc.update("""
                INSERT INTO learning_enrollment(learner_id,command_id,quiz_revision_id,item_count)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)
                """, learner.toString(), command.toString(), revision.toString(), items.size()));
        var rows = new java.util.ArrayList<Object[]>();
        for (var item : items) {
            rows.add(new Object[]{learner.toString(), command.toString(), rows.size(),
                    item.questionRevisionId().toString(), item.cycleId().toString(), item.cycleVersion()});
        }
        safe(() -> jdbc.batchUpdate("""
                INSERT INTO learning_enrollment_item(learner_id,command_id,position,question_revision_id,cycle_id,cycle_version)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,UUID_TO_BIN(?),UUID_TO_BIN(?),?)
                """, rows));
    }

    @Override public Optional<ResponseReceipt> response(UUID learner, UUID command) {
        return safe(() -> jdbc.query("""
                SELECT HEX(r.input_fingerprint), BIN_TO_UUID(r.session_id), BIN_TO_UUID(i.source_item_id),
                       r.item_position, BIN_TO_UUID(r.next_item_id), r.next_due_at
                FROM learning_session_response r
                JOIN learning_form_item i ON i.form_id=r.form_id AND i.position=r.item_position
                WHERE r.learner_id=UUID_TO_BIN(?) AND r.command_id=UUID_TO_BIN(?)
                """, (row, n) -> {
                    var due = row.getObject(6, LocalDateTime.class);
                    var accepted = new Acknowledgement(uuid(row.getString(2)), command, uuid(row.getString(3)),
                            new Progress(row.getInt(4) + 1, uuid(row.getString(5))),
                            due == null ? null : due.toInstant(ZoneOffset.UTC));
                    return new ResponseReceipt(row.getString(1).toLowerCase(Locale.ROOT), accepted);
                }, learner.toString(), command.toString()).stream().findFirst());
    }

    private static final String CAPTURES = """
            SELECT i.position, BIN_TO_UUID(c.question_revision_id), BIN_TO_UUID(c.id), c.cycle_version,
                   b.captured_sequence, state.sequence, c.strategy_id
            FROM learning_form_item i
            JOIN learning_session se ON se.id=UUID_TO_BIN(?)
            JOIN learning_cycle_session b ON b.session_id=se.id AND b.released=0
            JOIN learning_schedule_cycle c ON c.id=b.cycle_id AND c.question_revision_id=i.question_revision_id
              AND c.learner_id=se.learner_id AND c.quiz_id=se.quiz_id AND c.quiz_revision_id=se.quiz_revision_id
            LEFT JOIN learning_schedule_slot slot ON slot.active_cycle_id=c.id
            LEFT JOIN learning_fsrs_state state ON state.cycle_id=slot.active_cycle_id
            WHERE i.form_id=UUID_TO_BIN(?)
            """;

    @Override public Optional<Capture> capture(UUID session, UUID form, int position) {
        return captures(" AND i.position=?", session.toString(), form.toString(), position).stream().findFirst();
    }

    @Override public List<Capture> captures(UUID session, UUID form) {
        return captures(" ORDER BY i.position", session.toString(), form.toString());
    }

    private List<Capture> captures(String suffix, Object... parameters) {
        return safe(() -> jdbc.query(CAPTURES + suffix, (r, n) -> new Capture(r.getInt(1), uuid(r.getString(2)),
                new Expected(uuid(r.getString(3)), r.getLong(4), r.getLong(5)), r.getObject(6, Long.class),
                r.getString(7)), parameters));
    }

    @Override public void recordResponse(UUID learner, UUID form, Rating rating, String fingerprint, String response,
                                         Acknowledgement ack) {
        int position = ack.progress().reviewedItems() - 1;
        safe(() -> {
            require(jdbc.update("""
                    UPDATE learning_session_start SET reviewed_items=reviewed_items+1
                    WHERE session_id=UUID_TO_BIN(?) AND form_id=UUID_TO_BIN(?) AND reviewed_items=?
                    """, ack.sessionId().toString(), form.toString(), position) == 1, STALE_REVIEW);
            var next = ack.progress().nextItemId();
            jdbc.update("""
                    INSERT INTO learning_session_response(learner_id,command_id,session_id,form_id,item_position,
                        input_fingerprint,response_json,rating,next_item_id,next_due_at)
                    VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,UNHEX(?),?,?,UUID_TO_BIN(?),?)
                    """, learner.toString(), ack.commandId().toString(), ack.sessionId().toString(), form.toString(),
                    position, fingerprint, response, rating.name(), next == null ? null : next.toString(),
                    ack.nextDueAt() == null ? null : LocalDateTime.ofInstant(ack.nextDueAt(), ZoneOffset.UTC));
            return null;
        });
    }

    @Override public boolean completed(LearningSession session) {
        return safe(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM learning_session_start s
                    JOIN learning_form f ON f.id=s.form_id AND f.session_id=s.session_id
                    JOIN learning_session_response r ON r.session_id=s.session_id AND r.item_position=f.item_count-1
                    WHERE s.session_id=UUID_TO_BIN(?) AND r.learner_id=UUID_TO_BIN(?) AND s.reviewed_items=f.item_count)
                """, Boolean.class, session.id().toString(), session.learnerId().toString())));
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static <T> T safe(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException failure) { throw SafeDataAccess.sanitize(failure); }
    }
}
