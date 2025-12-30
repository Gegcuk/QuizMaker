package uk.gegc.quizmaker.features.media.infra.mapping;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.api.dto.UploadTargetDto;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.MediaAsset;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

@Component
public class MediaAssetMapper {

    private final MediaStorageProperties properties;

    public MediaAssetMapper(MediaStorageProperties properties) {
        this.properties = properties;
    }

    public MediaUploadResponse toUploadResponse(MediaAsset asset, PresignedPutObjectRequest presignedRequest) {
        return new MediaUploadResponse(
                asset.getId(),
                asset.getType(),
                asset.getStatus(),
                asset.getKey(),
                toCdnUrl(asset.getKey()),
                asset.getMimeType(),
                asset.getSizeBytes(),
                asset.getOriginalFilename(),
                asset.getArticleId(),
                asset.getCreatedBy(),
                asset.getCreatedAt(),
                toUploadTarget(presignedRequest)
        );
    }

    public MediaAssetResponse toResponse(MediaAsset asset) {
        if (asset == null) {
            return null;
        }
        return new MediaAssetResponse(
                asset.getId(),
                asset.getType(),
                asset.getStatus(),
                asset.getKey(),
                toCdnUrl(asset.getKey()),
                asset.getMimeType(),
                asset.getSizeBytes(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getOriginalFilename(),
                asset.getSha256(),
                asset.getArticleId(),
                asset.getCreatedBy(),
                asset.getCreatedAt(),
                asset.getUpdatedAt()
        );
    }

    private UploadTargetDto toUploadTarget(PresignedPutObjectRequest request) {
        Map<String, String> headers = request.signedHeaders() != null
                ? request.signedHeaders().entrySet().stream()
                .collect(
                        java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> String.join(", ", e.getValue())
                        )
                )
                : Collections.emptyMap();
        Instant expiresAt = request.expiration();
        return new UploadTargetDto(
                "PUT",
                request.url().toString(),
                headers,
                expiresAt
        );
    }

    private String toCdnUrl(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String base = properties.getCdnBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + key;
    }
}
