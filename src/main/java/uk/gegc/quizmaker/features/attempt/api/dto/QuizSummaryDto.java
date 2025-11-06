package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "QuizSummaryDto", description = "Lightweight quiz summary for embedded display in attempt lists")
public record QuizSummaryDto(
        @Schema(description = "UUID of the quiz", example = "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f")
        UUID id,

        @Schema(description = "Quiz title", example = "Java Fundamentals Quiz")
        String title,

        @Schema(description = "Number of questions in the quiz", example = "10")
        Integer questionCount,

        @Schema(description = "Category ID of the quiz", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID categoryId,

        @Schema(description = "Whether the quiz is publicly accessible", example = "true")
        Boolean isPublic
) {
}

