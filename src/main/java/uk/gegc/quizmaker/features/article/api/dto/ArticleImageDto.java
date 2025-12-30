package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Image reference with alt text and optional caption")
public record ArticleImageDto(
        @Schema(description = "Media asset identifier", example = "8b5b6c1a-....")
        UUID assetId,
        @Schema(description = "Required alt text", example = "A diagram of spaced repetition intervals")
        String alt,
        @Schema(description = "Optional caption", example = "Spaced repetition increases retention over time.")
        String caption
) {
}
