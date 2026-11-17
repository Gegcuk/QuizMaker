package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashMap;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Legacy binary scoring, including last-pair-wins for repeated left IDs. */
public final class MatchingScoringContractV1 implements VersionedScoringContract {
    @Override public ContractVersion version() { return SupportedQuestionContracts.MATCHING_SCORING; }

    @Override public ContractScore score(QuestionRevision question, JsonNode response) {
        var content = MatchingContentV1.read(question);
        MatchingContentV1.response(response);
        var pairs = new HashMap<Integer, Integer>();
        response.get("matches").forEach(pair -> pairs.put(pair.get("leftId").intValue(), pair.get("rightId").intValue()));
        boolean correct = true;
        for (var item : content.get("left"))
            correct &= Integer.valueOf(item.get("matchId").intValue()).equals(pairs.get(item.get("id").intValue()));
        return new ContractScore(correct ? BigDecimal.ONE : BigDecimal.ZERO, BigDecimal.ONE);
    }
}
