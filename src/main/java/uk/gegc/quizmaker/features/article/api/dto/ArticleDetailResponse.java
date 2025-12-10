package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response wrapper for single article fetch")
public record ArticleDetailResponse(
        @Schema(description = "Full article")
        ArticleDto article
) {
}
