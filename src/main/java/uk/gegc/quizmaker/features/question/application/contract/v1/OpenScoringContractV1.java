package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Locale;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Exact binary points; ROOT casing is the explicit #826 owner-approved legacy exception. */
public final class OpenScoringContractV1 implements VersionedScoringContract {
    @Override public ContractVersion version() { return SupportedQuestionContracts.OPEN_SCORING; }

    @Override public ContractScore score(QuestionRevision question, JsonNode response) {
        JsonNode content = OpenContentV1.read(question);
        OpenContentV1.response(response);
        boolean correct = normalize(content.get("answer").textValue()).equals(normalize(response.get("answer").textValue()));
        return new ContractScore(correct ? BigDecimal.ONE : BigDecimal.ZERO, BigDecimal.ONE);
    }

    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
}
