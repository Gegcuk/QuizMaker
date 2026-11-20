package uk.gegc.quizmaker.features.learning.infra.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.learning.application.LearningStudyService.EnrolledItem;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningStudyRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.CORRUPT;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcLearningStudyRepository implements LearningStudyRepository {
    private final JdbcTemplate jdbc;
    public JdbcLearningStudyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean prepared(UUID revisionId) {
        return !jdbc.queryForList("SELECT 1 FROM learning_prepared_publication WHERE revision_id=UUID_TO_BIN(?)",
                Integer.class, revisionId.toString()).isEmpty();
    }

    @Override public void recordPreparation(UUID revisionId) {
        jdbc.update("INSERT INTO learning_prepared_publication(revision_id) VALUES(UUID_TO_BIN(?))", revisionId.toString());
    }

    @Override public boolean shuffleQuestions(UUID quizId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT shuffle_learning_questions FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)",
                Boolean.class, quizId.toString()));
    }

    @Override public Optional<Start> start(UUID sessionId) {
        return jdbc.query("""
                SELECT requested_size, BIN_TO_UUID(form_id) FROM learning_session_start
                WHERE session_id=UUID_TO_BIN(?)
                """, (r,n) -> new Start(r.getInt(1), UUID.fromString(r.getString(2))), sessionId.toString())
                .stream().findFirst();
    }

    @Override public void recordStart(UUID sessionId, UUID formId, int limit, boolean shuffled) {
        jdbc.update("""
                INSERT INTO learning_session_start(session_id,form_id,requested_size,shuffled)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?)
                """, sessionId.toString(), formId.toString(), limit, shuffled);
    }

    @Override public Optional<Enrollment> enrollment(UUID learner, UUID command) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(quiz_revision_id),item_count FROM learning_enrollment
                WHERE learner_id=UUID_TO_BIN(?) AND command_id=UUID_TO_BIN(?)
                """, (r,n) -> new Enrollment(UUID.fromString(r.getString(1)),r.getInt(2)),
                learner.toString(),command.toString()).stream().findFirst();
    }

    @Override public List<EnrolledItem> enrolledItems(UUID learner, UUID command) {
        return jdbc.query("""
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
        }, learner.toString(), command.toString());
    }

    @Override public void recordEnrollment(UUID learner, UUID command, UUID revision, List<EnrolledItem> items) {
        jdbc.update("""
                INSERT INTO learning_enrollment(learner_id,command_id,quiz_revision_id,item_count)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)
                """, learner.toString(), command.toString(), revision.toString(), items.size());
        var rows = new java.util.ArrayList<Object[]>();
        for (var item : items) {
            rows.add(new Object[]{learner.toString(), command.toString(), rows.size(),
                    item.questionRevisionId().toString(), item.cycleId().toString(), item.cycleVersion()});
        }
        jdbc.batchUpdate("""
                INSERT INTO learning_enrollment_item(learner_id,command_id,position,question_revision_id,cycle_id,cycle_version)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,UUID_TO_BIN(?),UUID_TO_BIN(?),?)
                """, rows);
    }
}
