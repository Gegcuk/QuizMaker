package uk.gegc.quizmaker.features.question.infra.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuestionMediaResolver {

    private final MediaAssetService mediaAssetService;

    public MediaRefDto resolveAttachment(UUID assetId) {
        if (assetId == null) {
            return null;
        }
        Optional<MediaRefDto> resolved = mediaAssetService.getByIdForResolution(assetId);
        if (resolved.isEmpty()) {
            log.warn("Media attachment {} could not be resolved", assetId);
            return null;
        }
        return resolved.get();
    }

    public JsonNode resolveMediaInContent(JsonNode content) {
        if (content == null) {
            return null;
        }
        JsonNode copy = content.deepCopy();
        Map<UUID, Optional<MediaRefDto>> cache = new HashMap<>();
        resolveMediaInNode(copy, cache);
        return copy;
    }

    private void resolveMediaInNode(JsonNode node, Map<UUID, Optional<MediaRefDto>> cache) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            JsonNode mediaNode = objectNode.get("media");
            if (mediaNode != null && mediaNode.isObject()) {
                ObjectNode mediaObject = (ObjectNode) mediaNode;
                JsonNode assetIdNode = mediaObject.get("assetId");
                if (assetIdNode != null && assetIdNode.isTextual()) {
                    String assetIdRaw = assetIdNode.asText();
                    try {
                        UUID assetId = UUID.fromString(assetIdRaw);
                        Optional<MediaRefDto> resolved = cache.computeIfAbsent(
                                assetId,
                                mediaAssetService::getByIdForResolution
                        );
                        if (resolved.isPresent()) {
                            MediaRefDto mediaRef = resolved.get();
                            if (mediaRef.cdnUrl() != null) {
                                mediaObject.put("cdnUrl", mediaRef.cdnUrl());
                            }
                            if (mediaRef.width() != null) {
                                mediaObject.put("width", mediaRef.width());
                            }
                            if (mediaRef.height() != null) {
                                mediaObject.put("height", mediaRef.height());
                            }
                            if (mediaRef.mimeType() != null) {
                                mediaObject.put("mimeType", mediaRef.mimeType());
                            }
                        }
                    } catch (IllegalArgumentException ex) {
                        log.warn("Invalid media assetId format: {}", assetIdRaw);
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                resolveMediaInNode(fields.next().getValue(), cache);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                resolveMediaInNode(item, cache);
            }
        }
    }
}
