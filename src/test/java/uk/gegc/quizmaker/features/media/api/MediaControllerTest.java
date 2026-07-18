package uk.gegc.quizmaker.features.media.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.domain.Sort;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetSort;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.api.dto.UploadTargetDto;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MediaController.class)
@Import({
        OpenApiGroupConfig.class,
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        MediaControllerTest.SpringDocTestConfig.class
})
class MediaControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig {
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    MediaAssetService mediaAssetService;

    @MockitoBean
    AppPermissionEvaluator permissionEvaluator;

    @MockitoBean
    RateLimitService rateLimitService;

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
    @DisplayName("POST /api/v1/media/uploads returns RFC 7807 rate-limit response")
    void createUploadIntent_rateLimited() throws Exception {
        doThrow(new RateLimitExceededException("Too many requests for media-upload-intent", 12))
                .when(rateLimitService)
                .checkRateLimit(eq("media-upload-intent"), eq("user"), eq(10));

        MediaUploadRequest request = new MediaUploadRequest(MediaAssetType.IMAGE, "diagram.png", "image/png", 1024L, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/media/uploads")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.retryAfterSeconds").value(12));

        verifyNoInteractions(mediaAssetService);
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
        when(mediaAssetService.search(
                any(MediaAssetType.class),
                anyString(),
                anyInt(),
                anyInt(),
                any(MediaAssetSort.class),
                any(Sort.Direction.class),
                anyString()
        )).thenReturn(page);

        mockMvc.perform(get("/api/v1/media")
                        .param("type", "IMAGE")
                        .param("query", "diagram")
                        .param("sort", "ORIGINAL_FILENAME")
                .param("direction", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].cdnUrl").value(assetResponse.cdnUrl()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.pageable.pageNumber").value(0));
    }

    @Test
    @WithMockUser(authorities = "MEDIA_READ")
    @DisplayName("GET /api/v1/media returns RFC 7807 validation details for an unsupported sort field")
    void searchMedia_rejectsUnsupportedSortField() throws Exception {
        mockMvc.perform(get("/api/v1/media").param("sort", "UNSUPPORTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/type-mismatch"))
                .andExpect(jsonPath("$.title").value("Type Mismatch"))
                .andExpect(jsonPath("$.parameter").value("sort"))
                .andExpect(jsonPath("$.expectedType").value("MediaAssetSort"))
                .andExpect(jsonPath("$.providedValue").value("UNSUPPORTED"));

        verifyNoInteractions(mediaAssetService);
    }

    @Test
    @WithMockUser(authorities = "MEDIA_READ")
    @DisplayName("GET /api/v1/media/{id} returns an owned media asset")
    void getMedia() throws Exception {
        UUID assetId = assetResponse.assetId();
        when(mediaAssetService.getById(assetId, "user")).thenReturn(assetResponse);

        mockMvc.perform(get("/api/v1/media/{assetId}", assetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /v3/api-docs/media publishes the typed search, lookup, examples, and rate-limit contract without a database")
    void mediaOpenApiContract() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs/media"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        com.fasterxml.jackson.databind.JsonNode specification = objectMapper.readTree(body);
        assertThat(specification.path("paths").has("/api/v1/media")).isTrue();
        assertThat(specification.path("paths").has("/api/v1/media/{assetId}")).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1media/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/MediaAssetPageResponse");
        assertThat(specification.path("components").path("schemas").has("MediaAssetPageResponse")).isTrue();
        assertThat(specification.at("/paths/~1api~1v1~1media~1{assetId}/get/responses/404/content/application~1problem+json/examples/Deleted asset/value/status").asInt())
                .isEqualTo(404);
        assertThat(specification.at("/paths/~1api~1v1~1media/get/responses/400/content/application~1problem+json/examples/Invalid sort/value/providedValue").asText())
                .isEqualTo("UNSUPPORTED");
        assertThat(specification.at("/paths/~1api~1v1~1media~1uploads/post/responses/429").isMissingNode()).isFalse();
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
