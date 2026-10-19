package uk.gegc.quizmaker.features.question.infra.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuestionMediaResolverTest {

    @Mock
    private MediaAssetService mediaAssetService;

    private QuestionMediaResolver resolver;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        resolver = new QuestionMediaResolver(mediaAssetService);
        objectMapper = new ObjectMapper();
    }

    @Test
    void resolveAttachment_null_returnsNull() {
        assertNull(resolver.resolveAttachment(null));
    }

    @Test
    void resolveAttachment_missing_returnsNull() {
        UUID assetId = UUID.randomUUID();
        when(mediaAssetService.getByIdForResolution(assetId)).thenReturn(Optional.empty());

        assertNull(resolver.resolveAttachment(assetId));
    }

    @Test
    void resolveAttachment_valid_returnsMediaRef() {
        UUID assetId = UUID.randomUUID();
        MediaRefDto ref = new MediaRefDto(assetId, "https://cdn/x.png", null, null, 120, 80, "image/png");
        when(mediaAssetService.getByIdForResolution(assetId)).thenReturn(Optional.of(ref));

        MediaRefDto resolved = resolver.resolveAttachment(assetId);
        assertNotNull(resolved);
        assertEquals("https://cdn/x.png", resolved.cdnUrl());
        assertEquals("image/png", resolved.mimeType());
    }

    @Test
    void resolveMediaInContent_enrichesAndDoesNotMutateOriginal() throws Exception {
        UUID assetId = UUID.randomUUID();
        MediaRefDto ref = new MediaRefDto(assetId, "https://cdn/x.png", null, null, 120, 80, "image/png");
        when(mediaAssetService.getByIdsForResolution(Set.of(assetId))).thenReturn(Map.of(assetId, ref));

        JsonNode original = objectMapper.readTree("""
                {"options":[{"id":"a","text":"A","media":{"assetId":"%s"}}]}
                """.formatted(assetId));

        JsonNode resolved = resolver.resolveMediaInContent(original);

        assertNotSame(original, resolved);
        assertTrue(resolved.get("options").get(0).get("media").has("cdnUrl"));
        assertFalse(original.get("options").get(0).get("media").has("cdnUrl"));
    }

    @Test
    void resolveMediaInContent_batchesDistinctAssetsAndDoesNotRetryMissingAssets() throws Exception {
        UUID ready = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        MediaRefDto ref = new MediaRefDto(ready, "https://cdn/x.png", null, null, 120, 80, "image/png");
        when(mediaAssetService.getByIdsForResolution(Set.of(ready, missing))).thenReturn(Map.of(ready, ref));
        JsonNode original = objectMapper.readTree("""
                {"options":[{"media":{"assetId":"%s"}},
                            {"media":{"assetId":"%s"}},
                            {"media":{"assetId":"%s"}}]}
                """.formatted(ready, ready, missing));

        JsonNode resolved = resolver.resolveMediaInContent(original);

        assertEquals("https://cdn/x.png", resolved.at("/options/0/media/cdnUrl").asText());
        assertEquals("https://cdn/x.png", resolved.at("/options/1/media/cdnUrl").asText());
        assertTrue(resolved.at("/options/2/media/cdnUrl").isMissingNode());
        verify(mediaAssetService).getByIdsForResolution(Set.of(ready, missing));
        verifyNoMoreInteractions(mediaAssetService);
    }

    @Test
    void resolveMediaInContent_withoutMediaDoesNotQuery() throws Exception {
        assertNull(resolver.resolveMediaInContent(null));
        JsonNode content = objectMapper.readTree("{\"options\":[]}");
        assertEquals(content, resolver.resolveMediaInContent(content));
        verifyNoInteractions(mediaAssetService);
    }
}
