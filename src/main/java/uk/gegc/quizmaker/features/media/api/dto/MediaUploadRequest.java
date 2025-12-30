package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;

import java.util.UUID;

@Schema(description = "Request to create a presigned upload URL for a media asset")
public record MediaUploadRequest(
        @NotNull
        @Schema(description = "Asset type", example = "IMAGE")
        MediaAssetType type,

        @NotBlank
        @Schema(description = "Original filename (used for extension inference)", example = "diagram.png")
        String originalFilename,

        @NotBlank
        @Schema(description = "Mime type of the file", example = "image/png")
        String mimeType,

        @NotNull
        @Min(1)
        @Schema(description = "Expected file size in bytes", example = "834233")
        Long sizeBytes,

        @Schema(description = "Optional article ID to scope the asset under", example = "a6f0e7d2-2fbe-45a9-8fb3-02f40893a01e")
        UUID articleId
) {
}
