package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Single update item for bulk article operations")
public record ArticleBulkUpdateItem(
        @Schema(description = "Article identifier", example = "a7f9f0d2-7c4c-4a5e-9e9b-2f5c7b1d2a4c")
        UUID articleId,

        @Schema(description = "Payload used to update the article")
        ArticleUpsertRequest payload
) {
}
