package uk.gegc.quizmaker.features.question.application;

import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal boundary for already authorized callers using server-resolved owner context.
 * Owner scope is additional protection, not principal/permission/visibility authorization.
 */
public interface QuestionRevisionContractService {
    void bind(UUID ownerId, UUID quizRevisionId, List<QuestionContractBinding> bindings);
    Map<UUID, ResolvedQuestionContracts> resolve(UUID ownerId, UUID quizRevisionId);
}
