package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;

/**
 * Frozen five-family shape validator from active API v1 at 16abc7e. The resource and
 * validator are part of this exact contract, not a dispatch to the evolving active API.
 * A semantic change requires a new registered contract version and retained v1 support.
 */
final class FamilyShapeV1 {
    private static final int MAX_BYTES = 65_536;
    private static final int MAX_STRING = 10_000;
    private static final int MAX_ITEMS = 100;
    enum View { AUTHOR, RESPONSE }
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final JsonNode SHAPES = loadShapes();
    private FamilyShapeV1() { }

    private static JsonNode loadShapes() {
        try (var input = FamilyShapeV1.class.getResourceAsStream("/question-contracts/baseline-v1-shapes.json")) {
            if (input == null) throw new IllegalStateException("Baseline v1 question shapes are missing");
            return JSON.readTree(input);
        } catch (IOException failure) { throw new IllegalStateException("Baseline v1 question shapes cannot be read", failure); }
    }

    public static JsonNode read(QuestionType type, View view, String raw) {
        if (raw == null || raw.length() > MAX_BYTES
                || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new QuestionContractException("CONTENT_LIMIT", "content");
        }
        try {
            JsonNode value = JSON.readTree(raw);
            validate(type, view, value);
            return value;
        } catch (JsonProcessingException ex) {
            throw new QuestionContractException("INVALID_JSON", "content");
        }
    }

    public static void validate(QuestionType type, View view, JsonNode value) {
        if (type == null || view == null || !SHAPES.has(type.name())) {
            throw new QuestionContractException("UNKNOWN_QUESTION_FAMILY", "type");
        }
        if (view == View.AUTHOR && (value == null || !value.isObject())) {
            throw new QuestionContractException("INVALID_SHAPE", "content");
        }
        limits(value);
        check(SHAPES.get(type.name()).get(view.name()), value, view == View.RESPONSE ? "response" : "content");
        if (view == View.AUTHOR) {
            for (String field : java.util.List.of("options", "gaps", "items")) {
                if (!SHAPES.get(type.name()).get(view.name()).path("properties").has(field)) continue;
                if (!value.path(field).isArray()) continue;
                java.util.Set<JsonNode> ids = new java.util.HashSet<>();
                for (JsonNode item : value.get(field)) {
                    if (item.isObject() && item.has("id") && !ids.add(item.get("id"))) {
                        throw new QuestionContractException("DUPLICATE_ID", "content." + field);
                    }
                }
            }
        }
        if (view == View.RESPONSE && type == QuestionType.FILL_GAP) {
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (JsonNode answer : value.path("answers")) {
                if (!ids.add(answer.path("gapId").intValue())) throw new QuestionContractException("DUPLICATE_ID", "response.answers");
            }
        }
    }

    private static void limits(JsonNode value) {
        if (value == null) throw new QuestionContractException("INVALID_SHAPE", "content");
        // Bound serialization without constructing an unbounded second copy of input.
        try {
            JSON.writeValue(new OutputStream() {
                private int count;
                @Override public void write(int b) { add(1); }
                @Override public void write(byte[] b, int off, int len) { add(len); }
                private void add(int n) {
                    if (n > MAX_BYTES - count) throw new QuestionContractException("CONTENT_LIMIT", "content");
                    count += n;
                }
            }, value);
        } catch (IOException ex) {
            throw new QuestionContractException("CONTENT_LIMIT", "content");
        }
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(value);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            if (node.isTextual() && node.textValue().codePointCount(0, node.textValue().length()) > MAX_STRING
                    || node.isArray() && node.size() > MAX_ITEMS) {
                throw new QuestionContractException("CONTENT_LIMIT", "content");
            }
            if (node.isContainerNode()) node.elements().forEachRemaining(pending::add);
        }
    }

    private static void check(JsonNode shape, JsonNode value, String path) {
        if (value == null || value.isNull()) {
            if (value != null && shape.path("nullable").asBoolean()) return;
            throw new QuestionContractException("INVALID_SHAPE", path);
        }
        boolean valid = switch (shape.path("type").asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() && value.canConvertToInt();
            case "boolean" -> value.isBoolean();
            case "" -> true; // anyOf branches constrain only presence/text, not the whole object
            default -> false;
        };
        if (!valid) throw new QuestionContractException("INVALID_SHAPE", path);
        if (value.isIntegralNumber() && shape.has("minimum") && value.longValue() < shape.path("minimum").longValue()) {
            throw new QuestionContractException("INVALID_RANGE", path);
        }
        if (value.isIntegralNumber() && shape.has("maximum") && value.longValue() > shape.path("maximum").longValue()) {
            throw new QuestionContractException("INVALID_RANGE", path);
        }
        if (shape.has("enum") && !java.util.stream.StreamSupport.stream(shape.get("enum").spliterator(), false)
                .anyMatch(value::equals)) throw new QuestionContractException("UNSUPPORTED_CONTRACT_VERSION", path);
        if (value.isTextual()) {
            int length = value.textValue().codePointCount(0, value.textValue().length());
            if (length < shape.path("minLength").asInt(0) || length > shape.path("maxLength").asInt(MAX_STRING)) {
                throw new QuestionContractException("INVALID_LENGTH", path);
            }
            if ("uuid".equals(shape.path("format").asText())) {
                try {
                    if (!java.util.UUID.fromString(value.textValue()).toString().equalsIgnoreCase(value.textValue())) {
                        throw new IllegalArgumentException();
                    }
                }
                catch (IllegalArgumentException ex) { throw new QuestionContractException("INVALID_SHAPE", path); }
            }
        }
        if (value.isArray()) {
            if (value.size() < shape.path("minItems").asInt(0) || value.size() > shape.path("maxItems").asInt(MAX_ITEMS)) {
                throw new QuestionContractException("INVALID_LENGTH", path);
            }
            for (int i = 0; i < value.size(); i++) check(shape.path("items"), value.get(i), path + "[" + i + "]");
        }
        if (value.isObject()) {
            for (JsonNode required : shape.path("required")) {
                if (!value.has(required.asText())) throw new QuestionContractException("MISSING_FIELD", path + "." + required.asText());
            }
            Iterator<Map.Entry<String, JsonNode>> properties = shape.path("properties").fields();
            while (properties.hasNext()) {
                var property = properties.next();
                if (value.has(property.getKey())) check(property.getValue(), value.get(property.getKey()), path + "." + property.getKey());
            }
            if (shape.has("additionalProperties") && !shape.path("additionalProperties").asBoolean()) {
                var names = value.fieldNames();
                while (names.hasNext()) {
                    if (!shape.path("properties").has(names.next())) throw new QuestionContractException("FORBIDDEN_FIELD", path);
                }
            }
            if (shape.has("anyOf")) {
                boolean matched = false;
                for (JsonNode alternative : shape.get("anyOf")) {
                    try { check(alternative, value, path); matched = true; }
                    catch (QuestionContractException ignored) { /* Try the other supported representation. */ }
                }
                if (!matched) throw new QuestionContractException("MISSING_FIELD", path);
            }
        }
    }

}
