package uk.gegc.quizmaker.features.media.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetSort;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetService {
    MediaUploadResponse createUploadIntent(MediaUploadRequest request, String username);

    MediaAssetResponse finalizeUpload(UUID assetId, MediaUploadCompleteRequest request, String username);

    MediaAssetResponse getById(UUID assetId, String username);

    MediaAssetResponse getByIdForValidation(UUID assetId, String username);

    Optional<MediaRefDto> getByIdForResolution(UUID assetId);

    /**
     * Resolves publicly renderable READY images in bounded batches. Missing,
     * deleted, uploading, and non-image assets are absent from the result.
     * Null identifiers are ignored and duplicate identifiers are resolved once.
     */
    Map<UUID, MediaRefDto> getByIdsForResolution(Collection<UUID> assetIds);

    Page<MediaAssetResponse> search(
            MediaAssetType type,
            String query,
            int page,
            int size,
            MediaAssetSort sort,
            Sort.Direction direction,
            String username
    );

    void delete(UUID assetId, String username);
}
