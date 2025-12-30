package uk.gegc.quizmaker.features.media.application;

import org.springframework.data.domain.Page;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;

import java.util.UUID;

public interface MediaAssetService {
    MediaUploadResponse createUploadIntent(MediaUploadRequest request, String username);

    MediaAssetResponse finalizeUpload(UUID assetId, MediaUploadCompleteRequest request, String username);

    Page<MediaAssetResponse> search(MediaAssetType type, String query, int page, int size);

    void delete(UUID assetId, String username);
}
