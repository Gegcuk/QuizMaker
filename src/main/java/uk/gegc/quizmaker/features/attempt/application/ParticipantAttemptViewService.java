package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptDetailsResponse;

/** Online participant read. No command, grading or release mutation; unknown target data is never legacy. */
public interface ParticipantAttemptViewService {
    AttemptDetailsResponse read(UUID attemptId);
    uk.gegc.quizmaker.features.attempt.api.dto.AttemptReviewResponse review(UUID attemptId,
            boolean includeUserAnswers, boolean includeCorrectAnswers, boolean includeQuestionContext);
}
