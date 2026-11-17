package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Frozen COMPLIANCE v1 rules; never dispatch to the evolving authoring handler/schema. */
final class ComplianceContentV1 {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ComplianceContentV1() { }

    static JsonNode read(QuestionRevision question) {
        if (question == null || question.schemaVersion() != 1 || question.type() != QuestionType.COMPLIANCE)
            throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
        String raw = question.content();
        require(raw != null && raw.length() <= 65536 && raw.getBytes(StandardCharsets.UTF_8).length <= 65536);
        try {
            JsonNode content = JSON.readTree(raw);
            FamilyShapeV1.limits(content);
            require(content.isObject());
            var statements = content.path("statements");
            require(statements.isArray() && statements.size() >= 2 && statements.size() <= 6);
            var ids = new HashSet<Integer>();
            var keys = new ArrayList<String>();
            boolean compliant = false;
            for (var statement : statements) {
                require(statement.isObject());
                int id = integer(statement.path("id"));
                require(ids.add(id) && statement.path("compliant").isBoolean());
                compliant |= statement.get("compliant").booleanValue();
                if (statement.hasNonNull("text")) require(statement.get("text").isTextual());
                boolean media = statement.hasNonNull("media");
                if (media) {
                    var value = statement.get("media");
                    require(value.isObject() && value.path("assetId").isTextual());
                    try { require(UUID.fromString(value.path("assetId").textValue()).toString()
                            .equalsIgnoreCase(value.path("assetId").textValue())); }
                    catch (IllegalArgumentException invalid) { require(false); }
                    for (String field : List.of("cdnUrl", "mimeType"))
                        if (value.has(field)) require(value.get(field).isTextual());
                    for (String field : List.of("width", "height"))
                        if (value.has(field)) integer(value.get(field));
                }
                require(!statement.path("text").asText("").isBlank() || media);
                keys.add(Integer.toString(id));
            }
            require(compliant && keys.equals(question.options().stream().map(QuestionRevision.Option::sourceKey).toList()));
            return content;
        } catch (JsonProcessingException invalid) {
            throw new QuestionContractException("INVALID_JSON", "content");
        }
    }

    static void response(JsonNode response) {
        FamilyShapeV1.limits(response);
        require(response.isObject() && response.path("selectedStatementIds").isArray()
                && response.path("selectedStatementIds").size() <= 100);
        response.get("selectedStatementIds").forEach(ComplianceContentV1::integer);
    }

    static int integer(JsonNode value) {
        require(value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }
    private static void require(boolean valid) {
        if (!valid) throw new QuestionContractException("INVALID_SHAPE", "content");
    }
}
