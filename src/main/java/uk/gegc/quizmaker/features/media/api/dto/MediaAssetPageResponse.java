package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * OpenAPI representation of Spring Data's serialized Page response used by the media search endpoint.
 */
@Schema(name = "MediaAssetPageResponse", description = "A page of READY media assets. The content list is limited to assets owned by the caller unless the caller has a media, article, or system administrator permission.")
public record MediaAssetPageResponse(
        @ArraySchema(schema = @Schema(implementation = MediaAssetResponse.class))
        List<MediaAssetResponse> content,

        @Schema(description = "Number of result pages", example = "3")
        int totalPages,

        @Schema(description = "Total matching asset count", example = "42")
        long totalElements,

        @Schema(description = "Requested page size after server-side bounds are applied", example = "50")
        int size,

        @Schema(description = "Zero-based page number", example = "0")
        int number,

        @Schema(description = "Sort metadata")
        MediaAssetSortMetadata sort,

        @Schema(description = "Whether this is the first page", example = "true")
        boolean first,

        @Schema(description = "Whether this is the last page", example = "false")
        boolean last,

        @Schema(description = "Number of assets in content", example = "50")
        int numberOfElements,

        @Schema(description = "Whether the page has no assets", example = "false")
        boolean empty,

        @Schema(description = "Spring Data pagination metadata")
        MediaAssetPageableMetadata pageable
) {
}
