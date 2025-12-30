package uk.gegc.quizmaker.features.media.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.api.dto.UploadTargetDto;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MediaController.class)
class MediaControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    MediaAssetService mediaAssetService;

    @MockitoBean
    AppPermissionEvaluator permissionEvaluator;

    private MediaUploadResponse uploadResponse;
    private MediaAssetResponse assetResponse;

    @BeforeEach
    void setup() {
        when(permissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        when(permissionEvaluator.hasPermission(any(PermissionName.class))).thenReturn(true);

        UUID assetId = UUID.randomUUID();
        String key = "articles/123/" + assetId + ".png";
        uploadResponse = new MediaUploadResponse(
                assetId,
                MediaAssetType.IMAGE,
                MediaAssetStatus.UPLOADING,
                key,
                "https://cdn.quizzence.com/" + key,
                "image/png",
                1024L,
                "diagram.png",
                null,
                "writer",
                Instant.parse("2024-01-01T00:00:00Z"),
                new UploadTargetDto("PUT", "https://upload.test.com", Map.of("Content-Type", "image/png"), Instant.now().plusSeconds(900))
        );

        assetResponse = new MediaAssetResponse(
                assetId,
                MediaAssetType.IMAGE,
                MediaAssetStatus.READY,
                key,
                "https://cdn.quizzence.com/" + key,
                "image/png",
                1024L,
                640,
                480,
                "diagram.png",
                null,
                null,
                "writer",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z")
        );
    }

    @Test
    @WithMockUser(authorities = "MEDIA_CREATE")
    @DisplayName("POST /api/v1/media/uploads issues upload intent")
    void createUploadIntent() throws Exception {
        when(mediaAssetService.createUploadIntent(any(MediaUploadRequest.class), anyString())).thenReturn(uploadResponse);

        MediaUploadRequest request = new MediaUploadRequest(MediaAssetType.IMAGE, "diagram.png", "image/png", 1024L, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/media/uploads")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cdnUrl").value(uploadResponse.cdnUrl()))
                .andExpect(jsonPath("$.upload.url").value(uploadResponse.upload().url()));
    }

    @Test
    @WithMockUser(authorities = "MEDIA_CREATE")
    @DisplayName("POST /api/v1/media/uploads/{id}/complete finalizes upload")
    void finalizeUpload() throws Exception {
        UUID assetId = uploadResponse.assetId();
        when(mediaAssetService.finalizeUpload(any(UUID.class), any(MediaUploadCompleteRequest.class), anyString())).thenReturn(assetResponse);

        mockMvc.perform(post("/api/v1/media/uploads/{assetId}/complete", assetId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":640,\"height\":480}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.cdnUrl").value(assetResponse.cdnUrl()));
    }

    @Test
    @WithMockUser(authorities = "MEDIA_READ")
    @DisplayName("GET /api/v1/media returns paged assets")
    void searchMedia() throws Exception {
        Page<MediaAssetResponse> page = new PageImpl<>(List.of(assetResponse), PageRequest.of(0, 1), 1);
        when(mediaAssetService.search(any(MediaAssetType.class), anyString(), anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/v1/media")
                        .param("type", "IMAGE")
                        .param("query", "diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].cdnUrl").value(assetResponse.cdnUrl()));
    }

    @Test
    @WithMockUser(authorities = "MEDIA_DELETE")
    @DisplayName("DELETE /api/v1/media/{id} retires asset")
    void deleteMedia() throws Exception {
        UUID assetId = uploadResponse.assetId();

        mockMvc.perform(delete("/api/v1/media/{assetId}", assetId).with(csrf()))
                .andExpect(status().isNoContent());

        verify(mediaAssetService).delete(assetId, "user");
    }
}
