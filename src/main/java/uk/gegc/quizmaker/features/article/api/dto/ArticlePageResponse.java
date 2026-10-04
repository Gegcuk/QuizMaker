package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** OpenAPI representation of the existing Spring Data page JSON; not a new wire wrapper. */
@Schema(description = "Page of articles. Page numbers are zero-based; image dimensions are pixels.")
public record ArticlePageResponse(
        List<ArticleListItemDto> content,
        int totalPages,
        long totalElements,
        int size,
        int number,
        ArticleSortMetadata sort,
        boolean first,
        boolean last,
        int numberOfElements,
        boolean empty,
        ArticlePageableMetadata pageable
) {
    @Schema(description = "Applied Spring Data sort metadata")
    public record ArticleSortMetadata(boolean empty, boolean sorted, boolean unsorted) {
    }

    @Schema(description = "Applied Spring Data pagination metadata")
    public record ArticlePageableMetadata(int pageNumber, int pageSize, long offset,
                                          ArticleSortMetadata sort, boolean paged, boolean unpaged) {
    }
}
