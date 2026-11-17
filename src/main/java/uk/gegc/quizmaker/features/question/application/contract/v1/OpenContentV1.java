package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Frozen OPEN v1 shape/business checks; never dispatches to mutable authoring handlers. */
final class OpenContentV1 {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private OpenContentV1() { }

    static JsonNode read(QuestionRevision question) {
        if (question == null || question.schemaVersion() != 1 || question.type() != QuestionType.OPEN)
            throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
        require(question.options().isEmpty(), "content");
        String raw = question.content();
        if (raw.length() > 65_536 || raw.getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw new QuestionContractException("CONTENT_LIMIT", "content");
        try {
            JsonNode content = JSON.readTree(raw);
            answer(content, "content");
            require(!content.get("answer").textValue().isBlank(), "content.answer");
            return content;
        } catch (JsonProcessingException invalid) {
            throw new QuestionContractException("INVALID_JSON", "content");
        }
    }

    static void response(JsonNode response) { answer(response, "response"); }

    private static void answer(JsonNode value, String path) {
        FamilyShapeV1.limits(value);
        require(value != null && value.isObject(), path);
        require(value.has("answer") && value.get("answer").isTextual(), path + ".answer");
    }

    private static void require(boolean valid, String path) {
        if (!valid) throw new QuestionContractException("INVALID_SHAPE", path);
    }
}
