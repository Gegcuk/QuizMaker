package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Exact legacy all-or-nothing selection; duplicate selections score zero. */
public final class ComplianceScoringContractV1 implements VersionedScoringContract {
    @Override public ContractVersion version() { return SupportedQuestionContracts.COMPLIANCE_SCORING; }

    @Override public ContractScore score(QuestionRevision question, JsonNode response) {
        var content = ComplianceContentV1.read(question);
        ComplianceContentV1.response(response);
        var correct = new HashSet<Integer>();
        for (var statement : content.get("statements"))
            if (statement.get("compliant").booleanValue()) correct.add(statement.get("id").intValue());
        var selected = new HashSet<Integer>();
        boolean duplicate = false;
        for (var id : response.get("selectedStatementIds")) duplicate |= !selected.add(id.intValue());
        return new ContractScore(!duplicate && selected.equals(correct) ? BigDecimal.ONE : BigDecimal.ZERO, BigDecimal.ONE);
    }
}
