package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Paginated article list response for blog index")
public record ArticleListResponse(
        @Schema(description = "Article items for the current page")
        List<ArticleListItemDto> items,

        @Schema(description = "Total number of articles matching the filter", example = "123")
        long total,

        @Schema(description = "Requested page size", example = "20")
        int limit,

        @Schema(description = "Offset for pagination", example = "0")
        int offset
) {
}
