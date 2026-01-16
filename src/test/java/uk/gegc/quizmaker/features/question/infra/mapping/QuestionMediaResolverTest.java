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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

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
        when(mediaAssetService.getByIdForResolution(assetId)).thenReturn(Optional.of(ref));

        JsonNode original = objectMapper.readTree("""
                {"options":[{"id":"a","text":"A","media":{"assetId":"%s"}}]}
                """.formatted(assetId));

        JsonNode resolved = resolver.resolveMediaInContent(original);

        assertNotSame(original, resolved);
        assertTrue(resolved.get("options").get(0).get("media").has("cdnUrl"));
        assertFalse(original.get("options").get(0).get("media").has("cdnUrl"));
    }
}
