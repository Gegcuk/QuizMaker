package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Exact legacy binary grading: selecting any captured correct region earns one point. */
public final class HotspotScoringContractV1 implements VersionedScoringContract {
    @Override public ContractVersion version() { return SupportedQuestionContracts.HOTSPOT_SCORING; }

    @Override public ContractScore score(QuestionRevision question, JsonNode response) {
        var content = HotspotContentV1.read(question);
        int selected = HotspotContentV1.response(response);
        for (var region : content.path("regions"))
            if (region.path("id").intValue() == selected && region.path("correct").booleanValue())
                return new ContractScore(BigDecimal.ONE, BigDecimal.ONE);
        return new ContractScore(BigDecimal.ZERO, BigDecimal.ONE);
    }
}
