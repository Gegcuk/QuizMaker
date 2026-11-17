package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Frozen HOTSPOT v1 rules; never dispatch to the evolving authoring handler or schema. */
final class HotspotContentV1 {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private HotspotContentV1() { }

    static JsonNode read(QuestionRevision question) {
        if (question == null || question.schemaVersion() != 1 || question.type() != QuestionType.HOTSPOT)
            throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
        String raw = question.content();
        require(raw != null && raw.length() <= 65_536 && raw.getBytes(StandardCharsets.UTF_8).length <= 65_536);
        try {
            JsonNode content = JSON.readTree(raw);
            FamilyShapeV1.limits(content);
            require(content.isObject() && content.path("imageUrl").isTextual()
                    && !content.path("imageUrl").textValue().isBlank());
            text(content, "imageDescription", 2_000);
            var regions = content.path("regions");
            require(regions.isArray() && regions.size() >= 2 && regions.size() <= 6);
            var ids = new HashSet<Integer>();
            var keys = new ArrayList<String>();
            boolean correct = false;
            for (var region : regions) {
                require(region.isObject());
                int id = integer(region.path("id"));
                require(ids.add(id) && region.path("correct").isBoolean());
                correct |= region.get("correct").booleanValue();
                for (String field : List.of("x", "y", "width", "height")) require(integer(region.path(field)) >= 0);
                text(region, "label", 120);
                text(region, "description", 1_000);
                keys.add(Integer.toString(id));
            }
            // Zero dimensions, overlaps, signed IDs and any correct region retain their legacy meaning.
            require(correct && keys.equals(question.options().stream().map(QuestionRevision.Option::sourceKey).toList()));
            return content;
        } catch (JsonProcessingException invalid) {
            throw new QuestionContractException("INVALID_JSON", "content");
        }
    }

    static int response(JsonNode response) {
        FamilyShapeV1.limits(response);
        require(response.isObject());
        return integer(response.path("selectedRegionId"));
    }

    static String text(JsonNode parent, String field, int maximum) {
        var value = parent.get(field);
        if (value == null || value.isNull()) return null;
        require(value.isTextual());
        var text = value.textValue();
        require(text.codePointCount(0, text.length()) <= maximum);
        return text.isBlank() ? null : text;
    }

    private static int integer(JsonNode value) {
        require(value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }
    private static void require(boolean valid) {
        if (!valid) throw new QuestionContractException("INVALID_SHAPE", "content");
    }
}
