package uk.gegc.quizmaker.features.document.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * OpenAPI representation of the Spring Data page returned by the document list endpoint.
 */
@Schema(name = "DocumentPageResponse", description = "A page of documents owned by the authenticated user")
public record DocumentPageResponse(
        @ArraySchema(schema = @Schema(implementation = DocumentDto.class))
        List<DocumentDto> content,

        @Schema(description = "Number of result pages", example = "1")
        int totalPages,

        @Schema(description = "Total number of documents", example = "1")
        long totalElements,

        @Schema(description = "Requested page size", example = "10")
        int size,

        @Schema(description = "Zero-based page number", example = "0")
        int number,

        @Schema(description = "Applied sort metadata")
        DocumentPageSortMetadata sort,

        @Schema(description = "Whether this is the first page", example = "true")
        boolean first,

        @Schema(description = "Whether this is the last page", example = "true")
        boolean last,

        @Schema(description = "Number of documents in content", example = "1")
        int numberOfElements,

        @Schema(description = "Whether the page is empty", example = "false")
        boolean empty,

        @Schema(description = "Spring Data pagination metadata")
        DocumentPageableMetadata pageable
) {
}
