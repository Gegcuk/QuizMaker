package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;

import java.util.List;

@Schema(description = "Search criteria for filtering articles on list endpoints")
public record ArticleSearchCriteria(
        @Schema(description = "Article publication status to filter by", defaultValue = "PUBLISHED", example = "PUBLISHED")
        ArticleStatus status,

        @Schema(description = "Tags to filter by (case-insensitive match on tag name)", example = "[\"ai\", \"learning\"]")
        List<String> tags,

        @Schema(description = "Content group identifier for analytics and sitemap grouping", example = "blog")
        String contentGroup
) {
}
