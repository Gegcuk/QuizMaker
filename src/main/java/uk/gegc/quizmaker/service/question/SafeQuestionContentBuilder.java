package uk.gegc.quizmaker.service.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class SafeQuestionContentBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JsonNode buildSafeContent(QuestionType type, String originalContent) {
        try {
            JsonNode root = MAPPER.readTree(originalContent);

            return switch (type) {
                case MCQ_SINGLE, MCQ_MULTI -> buildSafeMcqContent(root);
                case TRUE_FALSE -> buildSafeTrueFalseContent();
                case FILL_GAP -> buildSafeFillGapContent(root);
                case OPEN -> buildSafeOpenContent();
                case COMPLIANCE -> buildSafeComplianceContent(root);
                case HOTSPOT -> buildSafeHotspotContent(root);
                case ORDERING -> buildSafeOrderingContent(root);
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
            safeOption.put("text", option.get("text").asText());
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
            safeStmt.put("text", stmt.get("text").asText());
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

    private JsonNode buildSafeOrderingContent(JsonNode root) {
        ObjectNode safe = MAPPER.createObjectNode();
        ArrayNode items = MAPPER.createArrayNode();

        // 🔀 Shuffle items for user to prevent pattern recognition
        List<JsonNode> itemList = new ArrayList<>();
        root.get("items").forEach(itemList::add);
        Collections.shuffle(itemList);

        itemList.forEach(item -> {
            ObjectNode safeItem = MAPPER.createObjectNode();
            safeItem.put("id", item.get("id").asInt());
            safeItem.put("text", item.get("text").asText());
            items.add(safeItem);
        });

        safe.set("items", items);
        return safe;
    }
} 