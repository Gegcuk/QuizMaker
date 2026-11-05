package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** Binary v1 points; no result persistence, normalization, release, clock or external service. */
public final class FamilyScoringContractV1 implements VersionedScoringContract {
    private final QuestionType type;
    private final ContractVersion version;

    public FamilyScoringContractV1(QuestionType type) {
        this.type = type;
        this.version = BaselineQuestionContracts.scoring(type);
    }

    @Override public ContractVersion version() { return version; }

    @Override public ContractScore score(QuestionRevision question, JsonNode response) {
        JsonNode content = FamilyContentV1.read(type, question);
        // Defend this port too: a malformed response must not become a synthetic zero score.
        FamilyShapeV1.validate(type, FamilyShapeV1.View.RESPONSE, response);
        boolean correct = switch (type) {
            case MCQ_SINGLE -> FamilyContentV1.values(content.get("options")).stream()
                    .filter(n -> n.path("correct").asBoolean()).findFirst().orElseThrow()
                    .get("id").textValue().equals(response.get("selectedOptionId").textValue());
            case MCQ_MULTI -> FamilyContentV1.values(content.get("options")).stream()
                    .filter(n -> n.path("correct").asBoolean()).map(n -> n.get("id").textValue()).collect(Collectors.toSet())
                    .equals(FamilyContentV1.values(response.get("selectedOptionIds")).stream().map(JsonNode::textValue).collect(Collectors.toSet()));
            case TRUE_FALSE -> content.get("answer").booleanValue() == response.get("answer").booleanValue();
            case ORDERING -> ordering(content).equals(FamilyContentV1.values(response.get("orderedItemIds")).stream().map(JsonNode::intValue).toList());
            case FILL_GAP -> fillGap(content, response);
            default -> throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        };
        return new ContractScore(correct ? BigDecimal.ONE : BigDecimal.ZERO, BigDecimal.ONE);
    }

    private static List<Integer> ordering(JsonNode content) {
        return content.has("correctOrder") ? FamilyContentV1.values(content.get("correctOrder")).stream().map(JsonNode::intValue).toList()
                : FamilyContentV1.values(content.get("items")).stream().map(n -> n.get("id").intValue()).toList();
    }

    private static boolean fillGap(JsonNode content, JsonNode response) {
        Map<Integer, String> given = new HashMap<>();
        for (var answer : response.get("answers"))
            given.put(answer.get("gapId").intValue(), FamilyContentV1.normalize(answer.get("answer").textValue()));
        return FamilyContentV1.values(content.get("gaps")).stream().allMatch(gap ->
                FamilyContentV1.normalize(gap.get("answer").textValue()).equals(given.get(gap.get("id").intValue())));
    }
}
