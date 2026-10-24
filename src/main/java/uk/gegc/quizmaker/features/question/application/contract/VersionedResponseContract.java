package uk.gegc.quizmaker.features.question.application.contract;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** A family adapter validates against captured immutable content, never mutable authoring rows. */
public interface VersionedResponseContract {
    ContractVersion version();
    void validate(QuestionRevision question, JsonNode response);
}
