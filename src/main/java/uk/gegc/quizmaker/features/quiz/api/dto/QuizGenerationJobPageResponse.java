package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * OpenAPI representation of the Spring Data page returned by the generation-jobs endpoint.
 */
@Schema(name = "QuizGenerationJobPageResponse", description = "A page of quiz generation jobs owned by the authenticated user")
public record QuizGenerationJobPageResponse(
        @ArraySchema(schema = @Schema(implementation = QuizGenerationStatus.class))
        List<QuizGenerationStatus> content,

        @Schema(description = "Number of result pages", example = "1")
        int totalPages,

        @Schema(description = "Total number of generation jobs", example = "1")
        long totalElements,

        @Schema(description = "Requested page size", example = "20")
        int size,

        @Schema(description = "Zero-based page number", example = "0")
        int number,

        @Schema(description = "Applied sort metadata")
        QuizGenerationJobPageSortMetadata sort,

        @Schema(description = "Whether this is the first page", example = "true")
        boolean first,

        @Schema(description = "Whether this is the last page", example = "true")
        boolean last,

        @Schema(description = "Number of generation jobs in content", example = "1")
        int numberOfElements,

        @Schema(description = "Whether the page is empty", example = "false")
        boolean empty,

        @Schema(description = "Spring Data pagination metadata")
        QuizGenerationJobPageableMetadata pageable
) {
}
