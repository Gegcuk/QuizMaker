package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Pagination metadata emitted by Spring Data for generation job pages")
public record QuizGenerationJobPageableMetadata(
        @Schema(description = "Zero-based page number", example = "0")
        int pageNumber,

        @Schema(description = "Page size", example = "20")
        int pageSize,

        @Schema(description = "Zero-based row offset", example = "0")
        long offset,

        @Schema(description = "Applied sort metadata")
        QuizGenerationJobPageSortMetadata sort,

        @Schema(description = "Whether pagination is active", example = "true")
        boolean paged,

        @Schema(description = "Whether pagination is disabled", example = "false")
        boolean unpaged
) {
}
