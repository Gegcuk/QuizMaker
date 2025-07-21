package uk.gegc.quizmaker.dto.attempt;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.dto.question.QuestionForAttemptDto;

import java.util.UUID;

@Schema(name = "StartAttemptResponse",
        description = "Attempt ID plus the first question (if any)")
public record StartAttemptResponse(
        @Schema(description = "Attempt UUID") UUID attemptId,
        @Schema(description = "First question to answer (safe, without correct answers)") QuestionForAttemptDto firstQuestion
) {
}
