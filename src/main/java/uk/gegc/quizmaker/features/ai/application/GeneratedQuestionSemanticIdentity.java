package uk.gegc.quizmaker.features.ai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates a conservative semantic identity for one generated question.
 * The identity is used only within one coverage evaluation and is never persisted.
 */
public final class GeneratedQuestionSemanticIdentity {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Set<String> ORDER_INSENSITIVE_ARRAYS = Set.of(
            "options",
            "gaps",
            "statements",
            "regions",
            "pairs",
            "rightChoices"
    );

    private GeneratedQuestionSemanticIdentity() {
    }

    public static Optional<Identity> from(Question question) {
        if (question == null
                || question.getType() == null
                || question.getQuestionText() == null
                || question.getContent() == null
                || question.getContent().isBlank()) {
            return Optional.empty();
        }

        String normalizedStem = normalizeText(question.getQuestionText());
        if (normalizedStem.isEmpty()) {
            return Optional.empty();
        }

        try {
            JsonNode content = OBJECT_MAPPER.readTree(question.getContent());
            if (content == null || !content.isObject()) {
                return Optional.empty();
            }

            JsonNode semanticContent = semanticContent(question.getType(), (ObjectNode) content);
            JsonNode canonicalContent = canonicalize(semanticContent, null);
            return Optional.of(new Identity(
                    question.getType(),
                    normalizedStem,
                    OBJECT_MAPPER.writeValueAsString(canonicalContent)
            ));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static JsonNode semanticContent(QuestionType type, ObjectNode content) {
        return switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> withoutIds(content, "options");
            case FILL_GAP -> withoutField(content, "options");
            case ORDERING -> orderingContent(content);
            case MATCHING -> matchingContent(content);
            case COMPLIANCE -> withoutIds(content, "statements");
            case HOTSPOT -> withoutIds(content, "regions");
            case TRUE_FALSE, OPEN -> content.deepCopy();
        };
    }

    private static ObjectNode withoutField(ObjectNode content, String fieldName) {
        ObjectNode copy = content.deepCopy();
        copy.remove(fieldName);
        return copy;
    }

    private static ObjectNode withoutIds(ObjectNode content, String arrayField) {
        ObjectNode copy = content.deepCopy();
        JsonNode values = copy.get(arrayField);
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException("Missing array field: " + arrayField);
        }
        for (JsonNode value : values) {
            if (!(value instanceof ObjectNode objectValue)) {
                throw new IllegalArgumentException("Expected object in array field: " + arrayField);
            }
            objectValue.remove("id");
        }
        return copy;
    }

    private static ObjectNode orderingContent(ObjectNode content) {
        JsonNode itemsNode = content.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            throw new IllegalArgumentException("ORDERING content is missing items");
        }

        Map<String, ObjectNode> itemsById = new HashMap<>();
        List<String> legacyOrder = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            if (!(itemNode instanceof ObjectNode item) || !item.has("id")) {
                throw new IllegalArgumentException("ORDERING item is missing id");
            }
            String id = item.get("id").asText();
            ObjectNode semanticItem = item.deepCopy();
            semanticItem.remove("id");
            itemsById.put(id, semanticItem);
            legacyOrder.add(id);
        }

        List<String> correctOrder = legacyOrder;
        JsonNode correctOrderNode = content.get("correctOrder");
        if (correctOrderNode != null) {
            if (!correctOrderNode.isArray()) {
                throw new IllegalArgumentException("ORDERING correctOrder must be an array");
            }
            correctOrder = new ArrayList<>();
            for (JsonNode id : correctOrderNode) {
                correctOrder.add(id.asText());
            }
        }

        ArrayNode orderedItems = JsonNodeFactory.instance.arrayNode();
        for (String id : correctOrder) {
            ObjectNode item = itemsById.get(id);
            if (item == null) {
                throw new IllegalArgumentException("ORDERING correctOrder references an unknown item");
            }
            orderedItems.add(item);
        }

        if (orderedItems.size() != itemsById.size()) {
            throw new IllegalArgumentException("ORDERING correctOrder does not cover every item");
        }

        ObjectNode semantic = JsonNodeFactory.instance.objectNode();
        semantic.set("orderedItems", orderedItems);
        return semantic;
    }

    private static ObjectNode matchingContent(ObjectNode content) {
        JsonNode leftNode = content.get("left");
        JsonNode rightNode = content.get("right");
        if (leftNode == null || !leftNode.isArray() || rightNode == null || !rightNode.isArray()) {
            throw new IllegalArgumentException("MATCHING content is missing left or right items");
        }

        Map<String, ObjectNode> rightById = new HashMap<>();
        ArrayNode rightChoices = JsonNodeFactory.instance.arrayNode();
        for (JsonNode rightItemNode : rightNode) {
            if (!(rightItemNode instanceof ObjectNode rightItem) || !rightItem.has("id")) {
                throw new IllegalArgumentException("MATCHING right item is missing id");
            }
            ObjectNode semanticRight = rightItem.deepCopy();
            semanticRight.remove("id");
            rightById.put(rightItem.get("id").asText(), semanticRight);
            rightChoices.add(semanticRight.deepCopy());
        }

        ArrayNode pairs = JsonNodeFactory.instance.arrayNode();
        for (JsonNode leftItemNode : leftNode) {
            if (!(leftItemNode instanceof ObjectNode leftItem)
                    || !leftItem.has("id")
                    || !leftItem.has("matchId")) {
                throw new IllegalArgumentException("MATCHING left item is missing id or matchId");
            }

            ObjectNode semanticLeft = leftItem.deepCopy();
            semanticLeft.remove(List.of("id", "matchId"));
            ObjectNode semanticRight = rightById.get(leftItem.get("matchId").asText());
            if (semanticRight == null) {
                throw new IllegalArgumentException("MATCHING matchId references an unknown right item");
            }

            ObjectNode pair = JsonNodeFactory.instance.objectNode();
            pair.set("left", semanticLeft);
            pair.set("right", semanticRight.deepCopy());
            pairs.add(pair);
        }

        ObjectNode semantic = JsonNodeFactory.instance.objectNode();
        semantic.set("pairs", pairs);
        semantic.set("rightChoices", rightChoices);
        return semantic;
    }

    private static JsonNode canonicalize(JsonNode node, String fieldName) {
        if (node.isObject()) {
            ObjectNode canonical = JsonNodeFactory.instance.objectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            for (String field : fields) {
                canonical.set(field, canonicalize(node.get(field), field));
            }
            return canonical;
        }

        if (node.isArray()) {
            List<JsonNode> values = new ArrayList<>();
            node.forEach(value -> values.add(canonicalize(value, null)));
            if (ORDER_INSENSITIVE_ARRAYS.contains(fieldName)) {
                values.sort(Comparator.comparing(JsonNode::toString));
            }
            ArrayNode canonical = JsonNodeFactory.instance.arrayNode();
            values.forEach(canonical::add);
            return canonical;
        }

        if (node.isTextual()) {
            return TextNode.valueOf(normalizeText(node.asText()));
        }

        return node.deepCopy();
    }

    private static String normalizeText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }

    public record Identity(
            QuestionType type,
            String normalizedStem,
            String canonicalContent
    ) {
    }
}
