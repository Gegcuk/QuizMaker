package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.api.dto.ParticipantAttemptView;

/** Online participant read. No command, grading or release mutation; unknown target data is never legacy. */
public interface ParticipantAttemptViewService {
    ParticipantAttemptView read(UUID attemptId);
    ParticipantAttemptView review(UUID attemptId,
            boolean includeUserAnswers, boolean includeCorrectAnswers, boolean includeQuestionContext);
}
