package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.article.domain.model.ArticleBlockType;

import java.util.UUID;

@Schema(description = "Structured content block to render article body")
public record ArticleBlockDto(
        @Schema(description = "Block type", example = "PARAGRAPH")
        ArticleBlockType type,
        @Schema(description = "Text content for paragraph/heading blocks", example = "Retrieval practice is more effective than re-reading.")
        String text,
        @Schema(description = "Referenced media asset (for image blocks)", example = "91b3....")
        UUID assetId,
        @Schema(description = "Alt text (required for images)", example = "Forgetting curve chart")
        String alt,
        @Schema(description = "Optional caption for images", example = "Ebbinghaus forgetting curve illustration")
        String caption,
        @Schema(description = "Alignment hint for images", example = "center")
        String align
) {
}
