package uk.gegc.quizmaker.features.question.application;

import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal boundary for already authorized callers using server-resolved owner context.
 * Owner scope is additional protection, not principal/permission/visibility authorization.
 * Uses REQUIRED transactions. Persistence/transaction failures during this call are sanitized;
 * a commit failure can have an unknown durable outcome. If joining an outer transaction,
 * its eventual completion and failure handling remain the caller's responsibility.
 */
public interface QuestionRevisionContractService {
    void bind(UUID ownerId, UUID quizRevisionId, List<QuestionContractBinding> bindings);
    Map<UUID, ResolvedQuestionContracts> resolve(UUID ownerId, UUID quizRevisionId);
}
