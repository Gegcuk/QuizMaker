package uk.gegc.quizmaker.features.attempt.api.dto;

import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.attempt.application.AttemptSubmissionService;

@Schema(name = "AttemptSubmitAcknowledgement", description = "Committed terminal submission, not a score, released result or grading completion.")
public record AttemptSubmitAcknowledgement(UUID attemptId, UUID submissionId, long stateVersion, boolean replayed) {
    public static AttemptSubmitAcknowledgement from(AttemptSubmissionService.Result value) {
        return new AttemptSubmitAcknowledgement(value.attemptId(), value.submissionId(), value.stateVersion(), value.replayed());
    }
}
