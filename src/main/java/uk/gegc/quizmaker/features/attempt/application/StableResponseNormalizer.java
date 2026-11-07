package uk.gegc.quizmaker.features.attempt.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.require;

/** Explicit transport-to-frozen-v1 adapter. No display indexes, mutable content or scoring calls. */
public final class StableResponseNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private StableResponseNormalizer() { }

    public static ObjectNode canonical(QuestionType type, JsonNode input) {
        require(type != null && input != null && input.isObject(), INVALID_INPUT);
        bounded(input);
        var result = JSON.createObjectNode();
        switch (type) {
            case MCQ_SINGLE -> result.put("selectedOptionId", option(input.path("selectedOptionId")));
            case MCQ_MULTI, ORDERING -> {
                String field = type == QuestionType.MCQ_MULTI ? "selectedOptionIds" : "orderedItemIds";
                var values = array(input.path(field)).stream().map(StableResponseNormalizer::option).toList();
                if (type == QuestionType.MCQ_MULTI) values = values.stream().distinct().sorted().toList();
                var target = result.putArray(field); values.forEach(target::add);
            }
            case TRUE_FALSE -> {
                require(input.path("answer").isBoolean(), INVALID_INPUT);
                result.put("answer", input.path("answer").booleanValue());
            }
            case FILL_GAP -> {
                var ids = new HashSet<Integer>();
                var answers = array(input.path("answers"));
                for (var answer : answers) {
                    require(answer.isObject() && answer.path("gapId").isIntegralNumber()
                            && answer.path("gapId").canConvertToInt() && ids.add(answer.path("gapId").intValue())
                            && answer.path("answer").isTextual(), INVALID_INPUT);
                    var text = answer.path("answer").textValue();
                    require(text.codePointCount(0, text.length()) <= 10000, INVALID_INPUT);
                }
                var target = result.putArray("answers");
                answers.stream().sorted(Comparator.comparingInt(a -> a.path("gapId").intValue())).forEach(a ->
                        target.addObject().put("gapId", a.path("gapId").intValue()).put("answer", a.path("answer").textValue()));
            }
            default -> throw new ResponseAcceptanceException(UNSUPPORTED);
        }
        return result;
    }

    public static ObjectNode bind(QuestionRevision question, ResolvedQuestionContracts contracts, ObjectNode stable) {
        require(contracts.response().version().equals(BaselineQuestionContracts.response(question.type())), UNSUPPORTED);
        var keys = question.options().stream().collect(Collectors.toMap(QuestionRevision.Option::id, QuestionRevision.Option::sourceKey));
        var result = stable.deepCopy();
        switch (question.type()) {
            case MCQ_SINGLE -> result.put("selectedOptionId", key(keys, stable.path("selectedOptionId")));
            case MCQ_MULTI -> {
                var values = result.putArray("selectedOptionIds");
                stable.path("selectedOptionIds").forEach(id -> values.add(key(keys, id)));
            }
            case ORDERING -> {
                var values = result.putArray("orderedItemIds");
                for (var id : stable.path("orderedItemIds")) {
                    try { values.add(Integer.parseInt(key(keys, id))); }
                    catch (NumberFormatException corrupt) { throw new ResponseAcceptanceException(CORRUPT); }
                }
            }
            case FILL_GAP -> {
                try {
                    var gaps = new HashSet<Integer>();
                    JSON.readTree(question.content()).path("gaps").forEach(g -> gaps.add(g.path("id").intValue()));
                    for (var answer : stable.path("answers")) require(gaps.contains(answer.path("gapId").intValue()), INVALID_INPUT);
                } catch (IOException corrupt) { throw new ResponseAcceptanceException(CORRUPT); }
            }
            case TRUE_FALSE -> { }
            default -> throw new ResponseAcceptanceException(UNSUPPORTED);
        }
        contracts.response().validate(question, result);
        bounded(result);
        return result;
    }

    private static String key(Map<UUID, String> keys, JsonNode id) {
        var key = keys.get(UUID.fromString(id.textValue()));
        require(key != null, INVALID_INPUT); return key;
    }
    private static String option(JsonNode node) {
        require(node.isTextual() && node.textValue().length() == 36, INVALID_INPUT);
        try {
            String id = UUID.fromString(node.textValue()).toString();
            require(id.equalsIgnoreCase(node.textValue()), INVALID_INPUT); return id;
        }
        catch (IllegalArgumentException invalid) { throw new ResponseAcceptanceException(INVALID_INPUT); }
    }
    private static List<JsonNode> array(JsonNode value) {
        require(value.isArray() && value.size() <= 100, INVALID_INPUT);
        var values = new ArrayList<JsonNode>(); value.forEach(values::add); return values;
    }
    private static void bounded(JsonNode value) {
        try {
            // Match the UTF-8 bytes of JsonNode.toString() stored below, including supplementary characters.
            JSON.writeValue(new OutputStreamWriter(new OutputStream() {
                int remaining = 65536;
                public void write(int b) { require(--remaining >= 0, INVALID_INPUT); }
                public void write(byte[] b, int offset, int length) { remaining -= length; require(remaining >= 0, INVALID_INPUT); }
            }, java.nio.charset.StandardCharsets.UTF_8), value);
        } catch (IOException invalid) { throw new ResponseAcceptanceException(INVALID_INPUT); }
    }
}
