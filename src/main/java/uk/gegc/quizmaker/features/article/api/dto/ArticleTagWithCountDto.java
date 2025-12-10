package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Tag with article usage count for content discovery")
public record ArticleTagWithCountDto(
        @Schema(description = "Tag name", example = "retrieval practice")
        String tag,

        @Schema(description = "Number of published articles using this tag", example = "5")
        Long count
) {
}
