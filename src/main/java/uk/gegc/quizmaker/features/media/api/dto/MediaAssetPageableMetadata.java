package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Pagination metadata emitted by Spring Data")
public record MediaAssetPageableMetadata(
        @Schema(description = "Zero-based page number", example = "0")
        int pageNumber,

        @Schema(description = "Page size", example = "50")
        int pageSize,

        @Schema(description = "Zero-based row offset", example = "0")
        long offset,

        @Schema(description = "Applied sort metadata")
        MediaAssetSortMetadata sort,

        @Schema(description = "Whether pagination is active", example = "true")
        boolean paged,

        @Schema(description = "Whether pagination is disabled", example = "false")
        boolean unpaged
) {
}
