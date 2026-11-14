package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.api.dto.ParticipantAttemptView;

/** Online participant read. No command, grading or release mutation; unknown target data is never legacy.
 * <p>V2 composition requires actual READ_COMMITTED; standalone owners set it explicitly.
 * See {@link uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions}
 * for ownership, rejection and JPA caller obligations. Legacy contracts are separate.
 */
public interface ParticipantAttemptViewService {
    ParticipantAttemptView read(UUID attemptId);
    ParticipantAttemptView review(UUID attemptId,
            boolean includeUserAnswers, boolean includeCorrectAnswers, boolean includeQuestionContext);
}
