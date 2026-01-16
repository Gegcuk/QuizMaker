package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class SafeQuestionContentBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Build safe content without correct answers.
     * For attempt contexts (during quiz taking), shuffling provides variety.
     * For review contexts (post-completion), deterministic output ensures consistency.
     *
     * @param type            the question type
     * @param originalContent the raw question content JSON
     * @return safe JSON content without answers
     */
    public JsonNode buildSafeContent(QuestionType type, String originalContent) {
        return buildSafeContent(type, originalContent, false);
    }

    /**
     * Build safe content without correct answers with optional deterministic mode.
     *
     * @param type            the question type
     * @param originalContent the raw question content JSON
     * @param deterministic   if true, avoid randomization (for review/caching); if false, shuffle where applicable (for attempt)
     * @return safe JSON content without answers
     */
    public JsonNode buildSafeContent(QuestionType type, String originalContent, boolean deterministic) {
        try {
            JsonNode root = MAPPER.readTree(originalContent);

            return switch (type) {
                case MCQ_SINGLE, MCQ_MULTI -> buildSafeMcqContent(root);
                case TRUE_FALSE -> buildSafeTrueFalseContent();
                case FILL_GAP -> buildSafeFillGapContent(root);
                case OPEN -> buildSafeOpenContent();
                case COMPLIANCE -> buildSafeComplianceContent(root);
                case HOTSPOT -> buildSafeHotspotContent(root);
                case ORDERING -> buildSafeOrderingContent(root, deterministic);
                case MATCHING -> buildSafeMatchingContent(root, deterministic);
            };
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build safe content for question", e);
        }
    }

    private JsonNode buildSafeMcqContent(JsonNode root) {
        ObjectNode safe = MAPPER.createObjectNode();
        ArrayNode options = MAPPER.createArrayNode();

        root.get("options").forEach(option -> {
            ObjectNode safeOption = MAPPER.createObjectNode();
            safeOption.put("id", option.get("id").asText());
            JsonNode textNode = option.get("text");
            if (textNode != null && !textNode.isNull()) {
                safeOption.put("text", textNode.asText());
            }
            copyMediaAssetId(safeOption, option);
            // 🔒 Deliberately omit "correct" field
            options.add(safeOption);
        });

        safe.set("options", options);
        return safe;
    }

    private JsonNode buildSafeTrueFalseContent() {
        // For TRUE_FALSE, no content needed - just the question text
        return MAPPER.createObjectNode();
    }

    private JsonNode buildSafeFillGapContent(JsonNode root) {
        ObjectNode safe = MAPPER.createObjectNode();
        safe.put("text", root.get("text").asText());

        ArrayNode gaps = MAPPER.createArrayNode();
        root.get("gaps").forEach(gap -> {
            ObjectNode safeGap = MAPPER.createObjectNode();
            safeGap.put("id", gap.get("id").asInt());
            // 🔒 Deliberately omit "answer" field
            gaps.add(safeGap);
        });

        safe.set("gaps", gaps);
        return safe;
    }

    private JsonNode buildSafeOpenContent() {
        // For OPEN questions, no content needed - just question text
        return MAPPER.createObjectNode();
    }

    private JsonNode buildSafeComplianceContent(JsonNode root) {
        ObjectNode safe = MAPPER.createObjectNode();
        ArrayNode statements = MAPPER.createArrayNode();

        root.get("statements").forEach(stmt -> {
            ObjectNode safeStmt = MAPPER.createObjectNode();
            safeStmt.put("id", stmt.get("id").asInt());
            JsonNode textNode = stmt.get("text");
            if (textNode != null && !textNode.isNull()) {
                safeStmt.put("text", textNode.asText());
            }
            copyMediaAssetId(safeStmt, stmt);
            // 🔒 Deliberately omit "compliant" field
            statements.add(safeStmt);
        });

        safe.set("statements", statements);
        return safe;
    }

    private JsonNode buildSafeHotspotContent(JsonNode root) {
        ObjectNode safe = MAPPER.createObjectNode();
        safe.put("imageUrl", root.get("imageUrl").asText());

        ArrayNode regions = MAPPER.createArrayNode();
        root.get("regions").forEach(region -> {
            ObjectNode safeRegion = MAPPER.createObjectNode();
            safeRegion.put("id", region.get("id").asInt());
            safeRegion.put("x", region.get("x").asInt());
            safeRegion.put("y", region.get("y").asInt());
            safeRegion.put("width", region.get("width").asInt());
            safeRegion.put("height", region.get("height").asInt());
            // 🔒 Deliberately omit "correct" field
            regions.add(safeRegion);
        });

        safe.set("regions", regions);
        return safe;
    }

    private JsonNode buildSafeOrderingContent(JsonNode root, boolean deterministic) {
        ObjectNode safe = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();

        List<JsonNode> itemList = new ArrayList<>();
        root.get("items").forEach(itemList::add);
        
        // 🔀 Shuffle items for user to prevent pattern recognition (only during attempt, not review)
        if (!deterministic) {
            Collections.shuffle(itemList);
        }

        itemList.forEach(item -> {
            ObjectNode safeItem = MAPPER.createObjectNode();
            safeItem.put("id", item.get("id").asInt());
            JsonNode textNode = item.get("text");
            if (textNode != null && !textNode.isNull()) {
                safeItem.put("text", textNode.asText());
            }
            copyMediaAssetId(safeItem, item);
            items.add(safeItem);
        });

        safe.set("items", items);
        return safe;
    }

    private JsonNode buildSafeMatchingContent(JsonNode root, boolean deterministic) {
        ObjectNode safe = MAPPER.createObjectNode();
        ArrayNode left = MAPPER.createArrayNode();
        ArrayNode right = MAPPER.createArrayNode();

        // Left side terms
        root.withArray("left").forEach(node -> {
            ObjectNode safeLeft = MAPPER.createObjectNode();
            safeLeft.put("id", node.get("id").asInt());
            JsonNode textNode = node.get("text");
            if (textNode != null && !textNode.isNull()) {
                safeLeft.put("text", textNode.asText());
            }
            copyMediaAssetId(safeLeft, node);
            left.add(safeLeft);
        });

        // Right side options (shuffled to avoid positional hints during attempt, stable for review)
        List<JsonNode> rightList = new ArrayList<>();
        root.withArray("right").forEach(rightList::add);
        if (!deterministic) {
            Collections.shuffle(rightList);
        }
        rightList.forEach(node -> {
            ObjectNode safeRight = MAPPER.createObjectNode();
            safeRight.put("id", node.get("id").asInt());
            JsonNode textNode = node.get("text");
            if (textNode != null && !textNode.isNull()) {
                safeRight.put("text", textNode.asText());
            }
            copyMediaAssetId(safeRight, node);
            // omit mapping/answer fields
            right.add(safeRight);
        });

        safe.set("left", left);
        safe.set("right", right);
        return safe;
    }

    private void copyMediaAssetId(ObjectNode target, JsonNode source) {
        if (source == null || target == null || !source.isObject()) {
            return;
        }
        JsonNode mediaNode = source.get("media");
        if (mediaNode == null || !mediaNode.isObject()) {
            return;
        }
        JsonNode assetIdNode = mediaNode.get("assetId");
        if (assetIdNode == null || !assetIdNode.isTextual()) {
            return;
        }
        String assetIdRaw = assetIdNode.asText();
        try {
            UUID.fromString(assetIdRaw);
        } catch (IllegalArgumentException ex) {
            return;
        }
        ObjectNode safeMedia = MAPPER.createObjectNode();
        safeMedia.put("assetId", assetIdRaw);
        target.set("media", safeMedia);
    }
}
