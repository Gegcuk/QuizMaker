package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidationException.Reason.INVALID_CONDITION;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidationException.Reason.UNSUPPORTED_OPERATOR;

/** The source item's accepted response supplies the score. No explicit reference is permitted. */
record ScenarioConditionV1(Score score, Integer priority) {
    enum Score { FULL_CREDIT, NOT_FULL_CREDIT }

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    static ScenarioConditionV1 parse(String condition, String path) {
        JsonNode root;
        try {
            root = JSON.readTree(condition);
        } catch (JsonProcessingException ignored) {
            throw new ScenarioGraphValidationException(INVALID_CONDITION, path);
        }

        if (root == null || !root.isObject() || !root.path("operator").isTextual() || !root.path("value").isTextual()
                || root.size() != (root.has("priority") ? 3 : 2)) {
            throw new ScenarioGraphValidationException(INVALID_CONDITION, path);
        }
        if (!"SCORE_IS".equals(root.get("operator").textValue())) {
            throw new ScenarioGraphValidationException(UNSUPPORTED_OPERATOR, path + ".operator");
        }
        Score score = switch (root.get("value").textValue()) {
            case "FULL_CREDIT" -> Score.FULL_CREDIT;
            case "NOT_FULL_CREDIT" -> Score.NOT_FULL_CREDIT;
            default -> throw new ScenarioGraphValidationException(INVALID_CONDITION, path + ".value");
        };
        JsonNode priority = root.get("priority");
        if (priority == null) return new ScenarioConditionV1(score, null);
        if (!priority.isIntegralNumber() || !priority.canConvertToInt() || priority.intValue() < 0) {
            throw new ScenarioGraphValidationException(INVALID_CONDITION, path + ".priority");
        }
        return new ScenarioConditionV1(score, priority.intValue());
    }
}
