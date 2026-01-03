package uk.gegc.quizmaker.features.media.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectAclRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.MediaAsset;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.features.media.domain.repository.MediaAssetRepository;
import uk.gegc.quizmaker.features.media.infra.mapping.MediaAssetMapper;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MediaAssetServiceImpl implements MediaAssetService {

    private static final int MAX_PAGE_SIZE = 200;

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaStorageProperties properties;
    private final S3Presigner presigner;
    private final S3Client s3Client;
    private final MediaAssetMapper mediaAssetMapper;
    private final AppPermissionEvaluator permissionEvaluator;

    @Override
    public MediaUploadResponse createUploadIntent(MediaUploadRequest request, String username) {
        validateUploadRequest(request);
        UUID assetId = UUID.randomUUID();
        String extension = resolveExtension(request.originalFilename(), request.mimeType());
        String key = buildObjectKey(request.articleId(), assetId, extension, request.type());

        MediaAsset asset = new MediaAsset();
        asset.setId(assetId);
        asset.setType(request.type());
        asset.setStatus(MediaAssetStatus.UPLOADING);
        asset.setKey(key);
        asset.setMimeType(request.mimeType());
        asset.setSizeBytes(request.sizeBytes());
        asset.setOriginalFilename(request.originalFilename());
        asset.setCreatedBy(StringUtils.hasText(username) ? username : "system");
        asset.setArticleId(request.articleId());

        MediaAsset saved = mediaAssetRepository.save(asset);
        PresignedPutObjectRequest presigned = presignUpload(saved);
        return mediaAssetMapper.toUploadResponse(saved, presigned);
    }

    @Override
    @Transactional(noRollbackFor = ResourceNotFoundException.class)
    public MediaAssetResponse finalizeUpload(UUID assetId, MediaUploadCompleteRequest request, String username) {
        MediaAsset asset = mediaAssetRepository.findByIdAndStatusNot(assetId, MediaAssetStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Media asset %s not found".formatted(assetId)));
        assertOwnership(asset, username);
        if (asset.getStatus() == MediaAssetStatus.READY) {
            makePublicIfImage(asset);
            return mediaAssetMapper.toResponse(asset);
        }

        HeadObjectResponse head = headObject(asset);
        long actualSize = head.contentLength();
        validateSize(asset.getType(), actualSize);
        String contentType = head.contentType();
        if (StringUtils.hasText(contentType) && !contentType.equalsIgnoreCase(asset.getMimeType())) {
            throw new ValidationException("Uploaded content type mismatch: expected %s but found %s"
                    .formatted(asset.getMimeType(), contentType));
        }

        if (asset.isImage()) {
            if (request == null || request.width() == null || request.height() == null) {
                throw new ValidationException("Width and height are required for image uploads");
            }
            asset.setWidth(request.width());
            asset.setHeight(request.height());
        }

        if (request != null && StringUtils.hasText(request.sha256())) {
            String normalized = request.sha256().trim().toLowerCase(Locale.ROOT);
            if (normalized.length() != 64 || !normalized.matches("^[0-9a-f]{64}$")) {
                throw new ValidationException("SHA-256 checksum must be a 64-character hexadecimal string");
            }
            asset.setSha256(normalized);
        }

        asset.setSizeBytes(actualSize);
        makePublicIfImage(asset);
        asset.setStatus(MediaAssetStatus.READY);
        MediaAsset saved = mediaAssetRepository.save(asset);
        return mediaAssetMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MediaAssetResponse> search(MediaAssetType type, String query, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        String normalizedQuery = StringUtils.hasText(query) ? query.trim() : null;
        return mediaAssetRepository.search(type, MediaAssetStatus.READY, normalizedQuery, pageable)
                .map(mediaAssetMapper::toResponse);
    }

    @Override
    public void delete(UUID assetId, String username) {
        MediaAsset asset = mediaAssetRepository.findByIdAndStatusNot(assetId, MediaAssetStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Media asset %s not found".formatted(assetId)));
        assertOwnership(asset, username);
        asset.setStatus(MediaAssetStatus.DELETED);
        mediaAssetRepository.save(asset);

        if (properties.isDeleteRemote() && StringUtils.hasText(asset.getKey())) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(asset.getKey())
                        .build());
            } catch (Exception ex) {
                log.warn("Failed to delete remote object {}: {}", asset.getKey(), ex.getMessage());
            }
        }
    }

    private void validateUploadRequest(MediaUploadRequest request) {
        if (request == null) {
            throw new ValidationException("Request body is required");
        }
        if (request.type() == null) {
            throw new ValidationException("Asset type is required");
        }
        if (!StringUtils.hasText(request.originalFilename())) {
            throw new ValidationException("Original filename is required");
        }
        if (!StringUtils.hasText(request.mimeType())) {
            throw new ValidationException("Mime type is required");
        }
        if (request.sizeBytes() == null || request.sizeBytes() <= 0) {
            throw new ValidationException("SizeBytes must be positive");
        }
        validateMime(request.type(), request.mimeType());
        validateSize(request.type(), request.sizeBytes());
    }

    private void validateMime(MediaAssetType type, String mimeType) {
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        if (type == MediaAssetType.IMAGE && !properties.getLimits().getAllowedImageMimeTypes().contains(normalized)) {
            throw new ValidationException("Mime type not allowed: " + mimeType);
        }
        if (type == MediaAssetType.DOCUMENT && !properties.getLimits().getAllowedDocumentMimeTypes().contains(normalized)) {
            throw new ValidationException("Mime type not allowed: " + mimeType);
        }
    }

    private void validateSize(MediaAssetType type, long sizeBytes) {
        if (type == MediaAssetType.IMAGE && sizeBytes > properties.getLimits().getMaxImageSizeBytes()) {
            throw new ValidationException("Image exceeds max size of " + properties.getLimits().getMaxImageSizeBytes() + " bytes");
        }
        if (type == MediaAssetType.DOCUMENT && sizeBytes > properties.getLimits().getMaxDocumentSizeBytes()) {
            throw new ValidationException("Document exceeds max size of " + properties.getLimits().getMaxDocumentSizeBytes() + " bytes");
        }
    }

    private String resolveExtension(String filename, String mimeType) {
        String ext = FilenameUtils.getExtension(filename);
        if (StringUtils.hasText(ext)) {
            return ext.toLowerCase(Locale.ROOT);
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            case "application/pdf" -> "pdf";
            default -> "";
        };
    }

    private String buildObjectKey(UUID articleId, UUID assetId, String extension, MediaAssetType type) {
        String basePrefix = articleId != null
                ? properties.getKeyPrefix().getArticles() + "/" + articleId
                : properties.getKeyPrefix().getLibrary();
        String suffix = StringUtils.hasText(extension) ? "." + extension : "";
        return basePrefix + "/" + assetId + suffix;
    }

    private PresignedPutObjectRequest presignUpload(MediaAsset asset) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(asset.getKey())
                .contentType(asset.getMimeType())
                .contentLength(asset.getSizeBytes())
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getUploadUrlTtl().getSeconds()))
                .putObjectRequest(putObjectRequest)
                .build();

        return presigner.presignPutObject(presignRequest);
    }

    private HeadObjectResponse headObject(MediaAsset asset) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(asset.getKey())
                    .build());
        } catch (NoSuchKeyException ex) {
            asset.setStatus(MediaAssetStatus.FAILED);
            mediaAssetRepository.save(asset);
            throw new ResourceNotFoundException("Uploaded object not found for " + asset.getId());
        }
    }

    private void makePublicIfImage(MediaAsset asset) {
        if (asset.getType() != MediaAssetType.IMAGE) {
            return;
        }
        try {
            s3Client.putObjectAcl(PutObjectAclRequest.builder()
                    .bucket(properties.getBucket())
                    .key(asset.getKey())
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .build());
        } catch (Exception ex) {
            log.error("Failed to set public ACL for asset {}: {}", asset.getId(), ex.getMessage(), ex);
            throw new IllegalStateException("Unable to publish uploaded image. Please try again.");
        }
    }

    private void assertOwnership(MediaAsset asset, String username) {
        boolean isOwner = StringUtils.hasText(username)
                && asset.getCreatedBy() != null
                && asset.getCreatedBy().equals(username);
        if (isOwner) {
            return;
        }
        boolean isAdmin = permissionEvaluator.hasAnyPermission(
                PermissionName.MEDIA_ADMIN,
                PermissionName.ARTICLE_ADMIN,
                PermissionName.SYSTEM_ADMIN
        );
        if (!isAdmin) {
            throw new ForbiddenException("You cannot modify this media asset");
        }
    }
}
