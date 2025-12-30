package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Response after creating an upload intent (presigned URL)")
public record MediaUploadResponse(
        @Schema(description = "Asset identifier", example = "8b5b6c1a-....")
        UUID assetId,
        @Schema(description = "Asset type", example = "IMAGE")
        MediaAssetType type,
        @Schema(description = "Lifecycle status", example = "UPLOADING")
        MediaAssetStatus status,
        @Schema(description = "Object key in Spaces", example = "articles/a6f0e7d2/.../8b5b6c1a.png")
        String key,
        @Schema(description = "Public CDN URL", example = "https://cdn.quizzence.com/articles/.../8b5b6c1a.png")
        String cdnUrl,
        @Schema(description = "Mime type", example = "image/png")
        String mimeType,
        @Schema(description = "Expected size in bytes", example = "834233")
        Long sizeBytes,
        @Schema(description = "Original filename", example = "diagram.png")
        String originalFilename,
        @Schema(description = "Article scope (if provided on upload)")
        UUID articleId,
        @Schema(description = "Username that created the asset")
        String createdBy,
        @Schema(description = "Created at timestamp", example = "2024-12-10T00:00:00Z")
        Instant createdAt,
        @Schema(description = "Upload target")
        UploadTargetDto upload
) {
}
