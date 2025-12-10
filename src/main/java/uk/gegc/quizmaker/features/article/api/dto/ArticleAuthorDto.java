package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Author details for the article")
public record ArticleAuthorDto(
        @Schema(description = "Author name", example = "Quizzence Team")
        String name,

        @Schema(description = "Author title or role", example = "Learning science & product")
        String title
) {
}
