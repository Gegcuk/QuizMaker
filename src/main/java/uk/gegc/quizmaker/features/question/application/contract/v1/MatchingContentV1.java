package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Frozen MATCHING v1 rules. No dispatch to the evolving authoring handler or schema. */
final class MatchingContentV1 {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private MatchingContentV1() { }

    static JsonNode read(QuestionRevision question) {
        if (question == null || question.schemaVersion() != 1 || question.type() != QuestionType.MATCHING)
            throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
        String raw = question.content();
        require(raw.length() <= 65536 && raw.getBytes(StandardCharsets.UTF_8).length <= 65536);
        try {
            JsonNode content = JSON.readTree(raw);
            FamilyShapeV1.limits(content);
            require(content.isObject());
            items(content.path("left"), true);
            var right = items(content.path("right"), false);
            var keys = new ArrayList<String>();
            for (var item : content.get("left")) {
                require(right.contains(integer(item.path("matchId"))));
                keys.add("left/" + item.path("id").intValue());
            }
            for (var item : content.get("right")) keys.add("right/" + item.path("id").intValue());
            require(keys.equals(question.options().stream().map(QuestionRevision.Option::sourceKey).toList()));
            return content;
        } catch (JsonProcessingException invalid) {
            throw new QuestionContractException("INVALID_JSON", "content");
        }
    }

    private static Set<Integer> items(JsonNode array, boolean left) {
        require(array.isArray() && array.size() >= 2 && array.size() <= 100);
        var ids = new HashSet<Integer>();
        for (var item : array) {
            require(item.isObject() && ids.add(integer(item.path("id"))));
            if (left) integer(item.path("matchId"));
            if (item.hasNonNull("text")) require(item.get("text").isTextual());
            boolean media = item.hasNonNull("media");
            if (media) {
                var value = item.get("media");
                require(value.isObject() && value.path("assetId").isTextual());
                try { require(UUID.fromString(value.path("assetId").textValue()).toString()
                        .equalsIgnoreCase(value.path("assetId").textValue())); }
                catch (IllegalArgumentException invalid) { require(false); }
                for (String field : List.of("cdnUrl", "mimeType"))
                    if (value.has(field)) require(value.get(field).isTextual());
                for (String field : List.of("width", "height"))
                    if (value.has(field)) integer(value.get(field));
            }
            require(!item.path("text").asText("").isBlank() || media);
        }
        return ids;
    }

    static void response(JsonNode response) {
        FamilyShapeV1.limits(response);
        require(response.isObject() && response.path("matches").isArray() && response.path("matches").size() <= 100);
        for (var pair : response.get("matches")) {
            require(pair.isObject());
            integer(pair.path("leftId")); integer(pair.path("rightId"));
        }
    }

    static int integer(JsonNode value) {
        require(value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }
    private static void require(boolean valid) {
        if (!valid) throw new QuestionContractException("INVALID_SHAPE", "content");
    }
}
