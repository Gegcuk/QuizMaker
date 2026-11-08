package uk.gegc.quizmaker.features.result.application.impl;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.stream.Collectors;
import uk.gegc.quizmaker.features.attempt.application.AttemptReadFactsService;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.Value;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.*;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import static uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.Category.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

/** Fixed five-family projection. No mutable Question entity, scoring, source-key leakage or arbitrary JSON copying. */
final class PracticeReviewProjector {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private PracticeReviewProjector() { }

    static List<Item> items(AttemptReadFactsService.Facts facts, AutomaticResult result, List<Value> responses,
                            ResultDisclosure.Decisions decisions) {
        var answers = responses.stream().collect(Collectors.toMap(Value::formItemId, a -> a));
        var questions = facts.revision().items().stream().collect(Collectors.toMap(i -> i.id(), i -> i.question()));
        var projected = new ArrayList<Item>();
        for (int index = 0; index < result.items().size(); index++) {
            var grade = result.items().get(index); var item = facts.form().items().get(index);
            var question = questions.get(item.sourceItemId()); var answer = answers.remove(item.id());
            var contracts = facts.contracts().get(question.id());
            require(contracts != null, CORRUPT);
            require(contracts.response().version().equals(
                    uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts.response(question.type()))
                    && contracts.scoring().version().equals(
                    uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts.scoring(question.type())), UNSUPPORTED);
            require(grade.formItemId().equals(item.id()) && grade.questionRevisionId().equals(question.id())
                    && Objects.equals(grade.responseId(), answer == null ? null : answer.id())
                    && grade.responseContract().equals(contracts.response().version())
                    && grade.scoringContract().equals(contracts.scoring().version()), CORRUPT);
            if (answer != null) require(answer.formId().equals(facts.form().id())
                    && answer.attemptId().equals(facts.access().attemptId()) && answer.questionRevisionId().equals(question.id())
                    && answer.contract().equals(grade.responseContract()), CORRUPT);
            // Validate captured content and accepted payload before selecting any field.
            contracts.response().optionLayout(question);
            JsonNode payload = answer == null ? null : parse(answer.normalizedResponse());
            if (payload != null) contracts.response().validate(question, payload);
            projected.add(new Item(item.id(), decisions.allows(QUESTION_SCORE) ? grade.earned() : null,
                    decisions.allows(CORRECTNESS) && answer != null ? grade.earned() == 1 : null,
                    decisions.allows(SUBMITTED_ANSWERS) ? (answer == null ? ResponseState.UNANSWERED : ResponseState.ACCEPTED) : null,
                    decisions.allows(SUBMITTED_ANSWERS) && answer != null ? submitted(question, payload) : null,
                    decisions.allows(CORRECT_ANSWER) ? correct(question) : null,
                    decisions.allows(EXPLANATION) && question.explanation() != null && !question.explanation().isBlank()
                            ? question.explanation() : null));
        }
        require(answers.isEmpty(), CORRUPT);
        return List.copyOf(projected);
    }
    private static JsonNode parse(String value) {
        try { return JSON.readTree(value); }
        catch (Exception invalid) { throw new ResultReleaseException(CORRUPT); }
    }
    private static Map<String, UUID> keys(QuestionRevision q) {
        return q.options().stream().collect(Collectors.toMap(QuestionRevision.Option::sourceKey, QuestionRevision.Option::id));
    }
    private static UUID option(Map<String, UUID> keys, JsonNode value) {
        var id = keys.get(value.asText()); require(id != null, CORRUPT); return id;
    }
    private static Answer submitted(QuestionRevision q, JsonNode response) {
        var keys = keys(q);
        return switch (q.type()) {
            case MCQ_SINGLE -> new Options(List.of(option(keys, response.path("selectedOptionId"))));
            case MCQ_MULTI -> new Options(values(response.path("selectedOptionIds")).stream().map(v -> option(keys, v)).toList());
            case TRUE_FALSE -> new BooleanAnswer(response.path("answer").booleanValue());
            case ORDERING -> new OrderedItems(values(response.path("orderedItemIds")).stream().map(v -> option(keys, v)).toList());
            case FILL_GAP -> new Gaps(values(response.path("answers")).stream()
                    .map(v -> new Gap(v.path("gapId").intValue(), v.path("answer").textValue())).toList());
            default -> throw new ResultReleaseException(UNSUPPORTED);
        };
    }
    private static Answer correct(QuestionRevision q) {
        var content = parse(q.content()); var keys = keys(q);
        return switch (q.type()) {
            case MCQ_SINGLE, MCQ_MULTI -> new Options(values(content.path("options")).stream()
                    .filter(v -> v.path("correct").booleanValue()).map(v -> option(keys, v.path("id"))).toList());
            case TRUE_FALSE -> new BooleanAnswer(content.path("answer").booleanValue());
            case ORDERING -> new OrderedItems(values(content.path("correctOrder")).stream().map(v -> option(keys, v)).toList());
            case FILL_GAP -> new Gaps(values(content.path("gaps")).stream()
                    .map(v -> new Gap(v.path("id").intValue(), v.path("answer").textValue())).toList());
            default -> throw new ResultReleaseException(UNSUPPORTED);
        };
    }
    private static List<JsonNode> values(JsonNode array) {
        var result = new ArrayList<JsonNode>(); array.forEach(result::add); return result;
    }
}
