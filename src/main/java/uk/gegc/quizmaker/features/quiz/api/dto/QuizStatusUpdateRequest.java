package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.model.quiz.QuizStatus;

@Schema(description = "Payload to change quiz status")
public record QuizStatusUpdateRequest(
        @NotNull
        @Schema(description = "Desired quiz status", example = "PUBLISHED")
        QuizStatus status
) {
}
