package uk.gegc.quizmaker.dto.attempt;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.model.attempt.AttemptMode;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "StartAttemptResponse",
        description = "Metadata for a newly started attempt. The first question is obtained via the getCurrentQuestion endpoint.")
public record StartAttemptResponse(
        @Schema(description = "Attempt UUID") UUID attemptId,
        @Schema(description = "Quiz UUID") UUID quizId,
        @Schema(description = "Selected attempt mode") AttemptMode mode,
        @Schema(description = "Total number of questions in the quiz") int totalQuestions,
        @Schema(description = "Time limit in minutes if timed; null if no time limit") Integer timeLimitMinutes,
        @Schema(description = "Attempt start timestamp (UTC)") Instant startedAt
) {
}
