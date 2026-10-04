package uk.gegc.quizmaker.features.article.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.media.api.dto.PublicImageRenditionDto;

import java.util.UUID;

@Schema(description = "Image reference with alt text and optional caption")
public record ArticleImageDto(
        @Schema(description = "Media asset identifier", example = "8b5b6c1a-55aa-4c22-9911-112233445566")
        UUID assetId,
        @Schema(description = "Required alt text", example = "A diagram of spaced repetition intervals")
        String alt,
        @Schema(description = "Optional caption", example = "Spaced repetition increases retention over time.")
        String caption,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        @Schema(description = "Public rendition resolved on article reads for a READY image. Null for missing, "
                + "deleted, uploading or non-image assets. Use as the social image fallback when ogImage is absent.",
                accessMode = Schema.AccessMode.READ_ONLY, nullable = true)
        PublicImageRenditionDto rendition
) {
    public ArticleImageDto(UUID assetId, String alt, String caption) {
        this(assetId, alt, caption, null);
    }
}
