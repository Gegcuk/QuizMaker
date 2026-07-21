package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Sort state emitted by Spring Data for generation job pages")
public record QuizGenerationJobPageSortMetadata(
        @Schema(example = "true")
        boolean sorted,

        @Schema(example = "false")
        boolean unsorted,

        @Schema(example = "false")
        boolean empty
) {
}
