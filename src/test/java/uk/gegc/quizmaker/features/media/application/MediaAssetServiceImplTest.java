package uk.gegc.quizmaker.features.media.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.MediaAsset;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.features.media.domain.repository.MediaAssetRepository;
import uk.gegc.quizmaker.features.media.infra.mapping.MediaAssetMapper;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MediaAssetServiceImplTest {

    @Mock
    private MediaAssetRepository mediaAssetRepository;
    @Mock
    private S3Presigner presigner;
    @Mock
    private S3Client s3Client;
    @Mock
    private AppPermissionEvaluator permissionEvaluator;

    private MediaStorageProperties properties;
    private MediaAssetMapper mapper;

    private uk.gegc.quizmaker.features.media.application.impl.MediaAssetServiceImpl service;

    @BeforeEach
    void setup() {
        properties = new MediaStorageProperties();
        properties.setCdnBaseUrl("https://cdn.test.com");
        properties.setEndpoint(URI.create("https://lon1.digitaloceanspaces.com"));
        mapper = new MediaAssetMapper(properties);
        service = new uk.gegc.quizmaker.features.media.application.impl.MediaAssetServiceImpl(
                mediaAssetRepository, properties, presigner, s3Client, mapper, permissionEvaluator
        );
    }

    @Test
    @DisplayName("createUploadIntent builds a stable key and presigned URL")
    void createUploadIntent_buildsKey() {
        UUID articleId = UUID.randomUUID();
        MediaUploadRequest request = new MediaUploadRequest(
                MediaAssetType.IMAGE,
                "diagram.png",
                "image/png",
                1200L,
                articleId
        );
        when(mediaAssetRepository.save(any(MediaAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(samplePresignedRequest());

        MediaUploadResponse response = service.createUploadIntent(request, "writer");

        assertThat(response.status()).isEqualTo(MediaAssetStatus.UPLOADING);
        assertThat(response.cdnUrl()).endsWith(response.key());
        assertThat(response.key()).contains(articleId.toString());
        assertThat(response.createdBy()).isEqualTo("writer");

        ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
        verify(mediaAssetRepository).save(captor.capture());
        assertThat(captor.getValue().getMimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("finalizeUpload validates dimensions for images and marks READY")
    void finalizeUpload_requiresDimensions() {
        UUID assetId = UUID.randomUUID();
        MediaAsset asset = new MediaAsset();
        asset.setId(assetId);
        asset.setType(MediaAssetType.IMAGE);
        asset.setStatus(MediaAssetStatus.UPLOADING);
        asset.setMimeType("image/png");
        asset.setKey("articles/" + assetId + ".png");
        asset.setCreatedBy("writer");

        when(mediaAssetRepository.findByIdAndStatusNot(assetId, MediaAssetStatus.DELETED)).thenReturn(Optional.of(asset));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(2048L)
                .contentType("image/png")
                .build());
        when(mediaAssetRepository.save(any(MediaAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MediaAssetResponse response = service.finalizeUpload(assetId, new MediaUploadCompleteRequest(640, 480, "a".repeat(64)), "writer");

        assertThat(response.status()).isEqualTo(MediaAssetStatus.READY);
        assertThat(response.sizeBytes()).isEqualTo(2048L);
        assertThat(response.width()).isEqualTo(640);
        assertThat(response.height()).isEqualTo(480);
    }

    @Test
    @DisplayName("finalizeUpload rejects missing width/height for images")
    void finalizeUpload_rejectsMissingDimensions() {
        UUID assetId = UUID.randomUUID();
        MediaAsset asset = new MediaAsset();
        asset.setId(assetId);
        asset.setType(MediaAssetType.IMAGE);
        asset.setStatus(MediaAssetStatus.UPLOADING);
        asset.setMimeType("image/png");
        asset.setKey("articles/" + assetId + ".png");
        asset.setCreatedBy("writer");

        when(mediaAssetRepository.findByIdAndStatusNot(assetId, MediaAssetStatus.DELETED)).thenReturn(Optional.of(asset));
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(1024L)
                .contentType("image/png")
                .build());

        assertThatThrownBy(() -> service.finalizeUpload(assetId, new MediaUploadCompleteRequest(null, null, null), "writer"))
                .isInstanceOf(ValidationException.class);
        verify(mediaAssetRepository, never()).save(any());
    }

    private PresignedPutObjectRequest samplePresignedRequest() {
        return PresignedPutObjectRequest.builder()
                .isBrowserExecutable(false)
                .signedHeaders(Map.of("Content-Type", List.of("image/png")))
                .httpRequest(SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.PUT)
                        .uri(URI.create("https://upload.test.com"))
                        .build())
                .expiration(Instant.now().plusSeconds(900))
                .build();
    }
}
