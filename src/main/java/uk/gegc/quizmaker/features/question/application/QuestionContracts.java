package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ProblemDetail;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The active API's v1 shapes, shared by validation and OpenAPI. This validates only
 * the schema vocabulary used below; grading and authoring business rules remain
 * in the existing question handlers. Input extensions are ignored, output is closed.
 */
public final class QuestionContracts {
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 65_536;
    public static final int MAX_STRING = 10_000;
    public static final int MAX_ITEMS = 100;
    public enum View { AUTHOR, SAFE, RESPONSE, CORRECT }

    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<QuestionType, Map<View, ObjectNode>> SHAPES = buildShapes();
    private static final JsonNode EXAMPLES = loadExamples();

    private QuestionContracts() { }

    public static String name(QuestionType type, View view) {
        StringBuilder name = new StringBuilder();
        for (String word : type.name().split("_")) {
            name.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return name + switch (view) {
            case AUTHOR -> "AuthorContentV1";
            case SAFE -> "SafeContentV1";
            case RESPONSE -> "ResponseV1";
            case CORRECT -> "CorrectAnswerV1";
        };
    }

    public static ObjectNode schema(QuestionType type, View view) {
        ObjectNode schema = SHAPES.get(type).get(view).deepCopy();
        standardNullTypes(schema);
        return schema;
    }

    private static void standardNullTypes(JsonNode node) {
        if (node instanceof ObjectNode object && object.path("nullable").asBoolean()) {
            String type = object.path("type").asText();
            object.remove("nullable");
            object.putArray("type").add(type).add("null");
        }
        if (node.isContainerNode()) node.elements().forEachRemaining(QuestionContracts::standardNullTypes);
    }

    public static JsonNode example(QuestionType type, View view) {
        return EXAMPLES.path(type.name()).path(view.name()).deepCopy();
    }

    public static JsonNode reviewExample(QuestionType type) {
        return EXAMPLES.path(type.name()).path("REVIEW").deepCopy();
    }

    private static JsonNode loadExamples() {
        try (var stream = QuestionContracts.class.getResourceAsStream("/question-contracts/v1-examples.json")) {
            if (stream == null) throw new IllegalStateException("Question contract examples are missing");
            return JSON.readTree(stream);
        } catch (IOException ex) { throw new IllegalStateException("Question contract examples cannot be read", ex); }
    }

    public static void requireVersion(JsonNode version) {
        if (version != null && (!version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() != VERSION)) {
            throw new QuestionContractException("UNSUPPORTED_CONTRACT_VERSION", "contractVersion");
        }
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
        if (type == null || view == null) {
            throw new QuestionContractException("UNKNOWN_QUESTION_FAMILY", "type");
        }
        if (view == View.AUTHOR && (value == null || !value.isObject())) {
            throw new QuestionContractException("INVALID_SHAPE", "content",
                    type == QuestionType.FILL_GAP ? "Content must be a JSON object" : "Invalid JSON for " + type + " question");
        }
        limits(value);
        check(SHAPES.get(type).get(view), value, view == View.RESPONSE ? "response" : "content");
        if (type == QuestionType.HOTSPOT && (view == View.AUTHOR || view == View.SAFE)) {
            HotspotSemantics.validate(value, false);
        }
        if (view == View.AUTHOR || view == View.SAFE) {
            for (String field : java.util.List.of("options", "gaps", "regions", "items", "statements", "left", "right")) {
                if (!SHAPES.get(type).get(view).path("properties").has(field)) continue;
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

    public static ProblemDetail unavailableProblem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.CONFLICT,
                "Question data is unavailable. The author must repair it; no answer or score is inferred.");
        problem.setType(uk.gegc.quizmaker.shared.api.problem.ErrorTypes.QUESTION_CONTENT_UNAVAILABLE);
        problem.setTitle("Question content unavailable");
        return problem;
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

    private static Map<QuestionType, Map<View, ObjectNode>> buildShapes() {
        Map<QuestionType, Map<View, ObjectNode>> families = new EnumMap<>(QuestionType.class);
        for (QuestionType type : QuestionType.values()) {
            Map<View, ObjectNode> views = new EnumMap<>(View.class);
            for (View view : View.values()) views.put(view, shape(type, view));
            families.put(type, views);
        }
        return families;
    }

    private static ObjectNode shape(QuestionType type, View view) {
        boolean author = view == View.AUTHOR;
        boolean safe = view == View.SAFE;
        ObjectNode result;
        if (view == View.RESPONSE || view == View.CORRECT) {
            boolean response = view == View.RESPONSE;
            result = switch (type) {
                case MCQ_SINGLE -> object(response, response ? "selectedOptionId" : "correctOptionId", text());
                case MCQ_MULTI -> object(response, response ? "selectedOptionIds" : "correctOptionIds", array(text(), 0, 100));
                case TRUE_FALSE -> object(response, "answer", scalar("boolean"));
                case OPEN -> object(response, "answer", response ? text() : nullable(text()));
                case HOTSPOT -> object(response, response ? "selectedRegionId" : "regionId", scalar("integer"));
                case ORDERING -> object(response, response ? "orderedItemIds" : "order", array(scalar("integer"), 0, 100));
                case COMPLIANCE -> object(response, response ? "selectedStatementIds" : "compliantIds", array(scalar("integer"), 0, 100));
                case FILL_GAP -> object(response, "answers", array(object(response,
                        response ? "gapId" : "id", scalar("integer"), response ? "answer" : "text", text()), 0, 100));
                case MATCHING -> object(response, response ? "matches" : "pairs", array(object(response,
                        "leftId", scalar("integer"), "rightId", scalar("integer")), 0, 100));
            };
        } else {
            result = switch (type) {
                case MCQ_SINGLE, MCQ_MULTI -> {
                    ObjectNode option = item(author, nonemptyText());
                    if (author) optional(option, "correct", scalar("boolean"));
                    yield object(author, "options", array(option, 2, 100));
                }
                case TRUE_FALSE -> safe ? object(false) : object(true, "answer", scalar("boolean"));
                case OPEN -> safe ? object(false) : object(true, "answer", nonemptyText());
                case FILL_GAP -> {
                    ObjectNode gap = object(author, "id", scalar("integer"));
                    if (author) required(gap, "answer", nonemptyText());
                    ObjectNode content = object(author, "text", nonemptyText(), "gaps", array(gap, 1, 100));
                    optional(content, "options", array(nonemptyText(), 1, FillGapContentValidator.MAX_OPTION_COUNT));
                    yield content;
                }
                case ORDERING -> {
                    ObjectNode content = object(author, "items", array(item(author, scalar("integer")), 2, 10));
                    if (author) optional(content, "correctOrder", array(scalar("integer"), 0, 10));
                    yield content;
                }
                case MATCHING -> {
                    ObjectNode left = item(author, scalar("integer"));
                    if (author) required(left, "matchId", scalar("integer"));
                    yield object(author, "left", array(left, 2, 100), "right", array(item(author, scalar("integer")), 2, 100));
                }
                case HOTSPOT -> {
                    ObjectNode region = object(author, "id", scalar("integer"), "x", scalar("integer"),
                            "y", scalar("integer").put("minimum", 0), "width", scalar("integer").put("minimum", 0), "height", scalar("integer").put("minimum", 0));
                    ((ObjectNode) region.path("properties").path("x")).put("minimum", 0);
                    if (author) required(region, "correct", scalar("boolean"));
                    optional(region, "label", semanticText(HotspotSemantics.LABEL_LIMIT, author));
                    optional(region, "description", semanticText(HotspotSemantics.REGION_DESCRIPTION_LIMIT, author));
                    ObjectNode content = object(author, "imageUrl", nonemptyText(), "regions", array(region, 2, 6));
                    optional(content, "imageDescription", semanticText(HotspotSemantics.IMAGE_DESCRIPTION_LIMIT, author));
                    content.put("description", "Optional authored descriptions. Missing descriptions never block manual authoring or attempts. "
                            + "AI output requires imageDescription and a distinct label plus description for every region. "
                            + "Descriptions are associated by region id, never array position. They must describe the image without identifying the correct choice.");
                    yield content;
                }
                case COMPLIANCE -> {
                    ObjectNode statement = item(author, scalar("integer"));
                    if (author) required(statement, "compliant", scalar("boolean"));
                    yield object(author, "statements", array(statement, 2, 6));
                }
            };
        }
        String familyDescription = result.path("description").asText("");
        result.put("description", (familyDescription.isEmpty() ? "" : familyDescription + " ") + "Contract v1 for " + type + " " + view + ". Maximum 65536 UTF-8 JSON bytes; strings 10000 characters; arrays 100 items unless a smaller bound is declared. "
                + (safe ? "Solution fields and all undeclared fields are forbidden."
                : view == View.CORRECT ? "Only declared released-answer fields are allowed; existing inclusion rules are unchanged."
                : "Unknown input fields are ignored; existing scoring and access rules are unchanged."));
        return result;
    }

    private static ObjectNode item(boolean author, ObjectNode id) {
        ObjectNode item = object(author, "id", id);
        optional(item, "text", author ? nullable(text()) : text());
        ObjectNode media = object(author, "assetId", text().put("format", "uuid"));
        // Existing resolved media metadata is public rendering data, not solution data.
        optional(media, "cdnUrl", text()); optional(media, "mimeType", text());
        optional(media, "width", scalar("integer")); optional(media, "height", scalar("integer"));
        optional(item, "media", author ? nullable(media.deepCopy()) : media);
        item.putArray("anyOf").add(object(true, "text", nonemptyText()));
        item.withArray("anyOf").add(object(true, "media", media));
        return item;
    }

    private static ObjectNode object(boolean extensions, Object... fields) {
        ObjectNode schema = scalar("object").put("additionalProperties", extensions);
        schema.putObject("properties"); schema.putArray("required");
        for (int i = 0; i < fields.length; i += 2) required(schema, (String) fields[i], (ObjectNode) fields[i + 1]);
        return schema;
    }
    private static void required(ObjectNode schema, String name, ObjectNode property) {
        optional(schema, name, property); schema.withArray("required").add(name);
    }
    private static void optional(ObjectNode schema, String name, ObjectNode property) {
        ((ObjectNode) schema.get("properties")).set(name, property);
    }
    private static ObjectNode array(ObjectNode items, int min, int max) {
        ObjectNode schema = scalar("array").put("minItems", min).put("maxItems", max);
        schema.set("items", items); return schema;
    }
    private static ObjectNode scalar(String type) {
        ObjectNode schema = JSON.createObjectNode().put("type", type);
        if (type.equals("integer")) schema.put("minimum", Integer.MIN_VALUE).put("maximum", Integer.MAX_VALUE);
        return schema;
    }
    private static ObjectNode text() { return scalar("string").put("maxLength", MAX_STRING); }
    private static ObjectNode semanticText(int maximum, boolean author) {
        ObjectNode text = scalar("string").put("maxLength", maximum);
        if (author) return nullable(text);
        return text.put("minLength", 1);
    }
    private static ObjectNode nonemptyText() { return text().put("minLength", 1); }
    private static ObjectNode nullable(ObjectNode schema) { return schema.put("nullable", true); }
}
