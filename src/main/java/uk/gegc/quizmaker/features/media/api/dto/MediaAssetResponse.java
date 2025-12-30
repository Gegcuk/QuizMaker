package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Media asset metadata and public URL")
public record MediaAssetResponse(
        @Schema(description = "Asset identifier", example = "8b5b6c1a-....")
        UUID assetId,
        @Schema(description = "Asset type", example = "IMAGE")
        MediaAssetType type,
        @Schema(description = "Lifecycle status", example = "READY")
        MediaAssetStatus status,
        @Schema(description = "Object key in Spaces", example = "articles/a6f0e7d2/.../8b5b6c1a.png")
        String key,
        @Schema(description = "Public CDN URL", example = "https://cdn.quizzence.com/articles/.../8b5b6c1a.png")
        String cdnUrl,
        @Schema(description = "Mime type", example = "image/png")
        String mimeType,
        @Schema(description = "Size in bytes", example = "834233")
        Long sizeBytes,
        @Schema(description = "Pixel width (images)", example = "1280")
        Integer width,
        @Schema(description = "Pixel height (images)", example = "720")
        Integer height,
        @Schema(description = "Original filename", example = "diagram.png")
        String originalFilename,
        @Schema(description = "Optional SHA-256 checksum", example = "a3b5c6...")
        String sha256,
        @Schema(description = "Article scope (if provided on upload)")
        UUID articleId,
        @Schema(description = "Username that created the asset")
        String createdBy,
        @Schema(description = "Created at timestamp", example = "2024-12-10T00:00:00Z")
        Instant createdAt,
        @Schema(description = "Updated at timestamp", example = "2024-12-11T00:00:00Z")
        Instant updatedAt
) {
}
