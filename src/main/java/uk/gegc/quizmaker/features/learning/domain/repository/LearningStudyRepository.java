package uk.gegc.quizmaker.features.learning.domain.repository;

import uk.gegc.quizmaker.features.learning.application.LearningStudyService.EnrolledItem;
import uk.gegc.quizmaker.features.learning.domain.model.LearningReview.Acknowledgement;
import uk.gegc.quizmaker.features.learning.domain.model.LearningReview.Mode;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Expected;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public-command evidence; all operations require the owning transaction. */
public interface LearningStudyRepository {
    record Start(int limit, UUID formId, Mode mode, UUID selectedQuestion, int reviewedItems, boolean superseded) { }
    record Enrollment(UUID revisionId, int count) { }
    record Capture(int position, UUID questionRevisionId, Expected captured, Long activeSequence, String strategy) { }
    record ResponseReceipt(String fingerprint, Acknowledgement acknowledgement) {
        @Override public String toString() { return "LearningResponseReceipt[redacted]"; }
    }

    boolean prepared(UUID revisionId);
    void recordPreparation(UUID revisionId);
    boolean shuffleQuestions(UUID quizId);
    Optional<Start> start(UUID sessionId);
    Optional<Start> lockStart(UUID sessionId);
    void recordStart(UUID sessionId, UUID formId, int limit, boolean shuffled, Mode mode, UUID selectedQuestion);
    Optional<Enrollment> enrollment(UUID learner, UUID command);
    List<EnrolledItem> enrolledItems(UUID learner, UUID command);
    void recordEnrollment(UUID learner, UUID command, UUID revision, List<EnrolledItem> items);

    Optional<ResponseReceipt> response(UUID learner, UUID command);
    /** A saved scheduled custom capture writes the shared custom history; FSRS/practice do not. */
    boolean responseCommandConflicts(UUID learner, UUID session, UUID sourceItem, UUID command);
    Optional<Capture> capture(UUID session, UUID form, int position);
    List<Capture> captures(UUID session, UUID form);
    /** Close other sessions with an unanswered capture of the step just advanced by the winning session. */
    void supersedeOverlaps(UUID winningSession, Expected advancedStep);
    void recordResponse(UUID learner, UUID form, Rating rating, String fingerprint, String response,
                        Acknowledgement acknowledgement);
}
