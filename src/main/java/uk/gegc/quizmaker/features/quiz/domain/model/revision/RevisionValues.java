package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.TreeMap;

final class RevisionValues {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private RevisionValues() { }

    static void require(boolean valid) {
        if (!valid) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_CONTENT);
    }

    static void version(int version) {
        if (version != 1) throw new RevisionIntegrityException(RevisionIntegrityException.Reason.UNSUPPORTED_VERSION);
    }

    static String text(String value, int maximum, boolean required) {
        require(value != null ? value.length() <= maximum && (!required || !value.isBlank()) : !required);
        return value;
    }

    static Instant timestamp(Instant value) {
        require(value != null);
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    // Canonicalize object keys without rounding numbers. Persist this string as text, never MySQL JSON.
    // Array order and decimal precision/scale are retained in the accepted snapshot.
    static String json(String value) {
        require(value != null);
        try {
            JsonNode node = JSON.readTree(value);
            require(node != null && node.isObject());
            return ordered(node).toString();
        } catch (JsonProcessingException exception) {
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_CONTENT);
        }
    }

    private static JsonNode ordered(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> result.set(key, ordered(value)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(value -> result.add(ordered(value)));
            return result;
        }
        return node;
    }
}
