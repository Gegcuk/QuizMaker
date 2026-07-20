package uk.gegc.quizmaker.features.bugreport.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * OpenAPI representation of the Spring Data page returned by the admin list endpoint.
 */
@Schema(description = "A page of bug reports visible to system administrators")
public record BugReportPageResponse(
        @ArraySchema(schema = @Schema(implementation = BugReportDto.class))
        List<BugReportDto> content,

        @Schema(description = "Total number of result pages", example = "3")
        int totalPages,

        @Schema(description = "Total number of matching bug reports", example = "42")
        long totalElements,

        @Schema(description = "Applied page size", example = "20")
        int size,

        @Schema(description = "Zero-based page number", example = "0")
        int number,

        @Schema(description = "Applied sort metadata")
        BugReportSortMetadata sort,

        @Schema(description = "Whether this is the first page", example = "true")
        boolean first,

        @Schema(description = "Whether this is the last page", example = "false")
        boolean last,

        @Schema(description = "Number of reports in content", example = "20")
        int numberOfElements,

        @Schema(description = "Whether this page has no reports", example = "false")
        boolean empty,

        @Schema(description = "Spring Data pagination metadata")
        BugReportPageableMetadata pageable
) {
}
