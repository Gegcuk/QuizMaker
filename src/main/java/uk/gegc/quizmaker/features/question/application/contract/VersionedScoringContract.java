package uk.gegc.quizmaker.features.question.application.contract;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractScore;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Implementations are supplied by family-owned increments; the registry supplies no fallback algorithm. */
public interface VersionedScoringContract {
    ContractVersion version();
    ContractScore score(QuestionRevision question, JsonNode validatedResponse);
}
