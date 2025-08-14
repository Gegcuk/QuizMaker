package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;

@Schema(description = "Payload to change quiz status")
public record QuizStatusUpdateRequest(
        @NotNull
        @Schema(description = "Desired quiz status", example = "PUBLISHED")
        QuizStatus status
) {
}
