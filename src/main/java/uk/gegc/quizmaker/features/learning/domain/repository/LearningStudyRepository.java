package uk.gegc.quizmaker.features.learning.domain.repository;

import uk.gegc.quizmaker.features.learning.application.LearningStudyService.EnrolledItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public-command evidence; all operations require the owning transaction. */
public interface LearningStudyRepository {
    record Start(int limit, UUID formId) { }
    record Enrollment(UUID revisionId, int count) { }

    boolean prepared(UUID revisionId);
    void recordPreparation(UUID revisionId);
    boolean shuffleQuestions(UUID quizId);
    Optional<Start> start(UUID sessionId);
    void recordStart(UUID sessionId, UUID formId, int limit, boolean shuffled);
    Optional<Enrollment> enrollment(UUID learner, UUID command);
    List<EnrolledItem> enrolledItems(UUID learner, UUID command);
    void recordEnrollment(UUID learner, UUID command, UUID revision, List<EnrolledItem> items);
}
