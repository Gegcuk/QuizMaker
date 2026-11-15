package uk.gegc.quizmaker.features.question.application;

import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractScopeIntent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Internal boundary for already authorized callers using server-resolved owner context.
 * Owner scope is additional protection, not principal/permission/visibility authorization.
 * Uses REQUIRED transactions. Persistence/transaction failures during this call are sanitized;
 * a commit failure can have an unknown durable outcome. If joining an outer transaction,
 * its eventual completion and failure handling remain the caller's responsibility.
 * An external transaction must declare its complete quiz-revision set before bind/resolve.
 * The set cannot expand until that transaction completes. READ uses shared identity locks;
 * WRITE reserves exclusive locks before any resolution/binding. READ cannot upgrade to WRITE.
 * Standalone resolve/bind own a singleton READ/WRITE scope respectively.
 */
public interface QuestionRevisionContractService {
    default void declareScope(UUID ownerId, Set<UUID> quizRevisionIds) {
        declareScope(ownerId, quizRevisionIds, ContractScopeIntent.READ);
    }
    void declareScope(UUID ownerId, Set<UUID> quizRevisionIds, ContractScopeIntent intent);
    /**
     * Publication replay only, inside the caller's transaction. An exact complete set may
     * establish a singleton READ scope; false leaves declaration to the normal WRITE path.
     * An already declared caller scope is never weakened or upgraded. Repeated exact
     * publication replays can reuse their own READ scope; later expansion remains terminal.
     */
    boolean replayIfExact(UUID ownerId, UUID quizRevisionId, List<QuestionContractBinding> bindings);

    void bind(UUID ownerId, UUID quizRevisionId, List<QuestionContractBinding> bindings);
    Map<UUID, ResolvedQuestionContracts> resolve(UUID ownerId, UUID quizRevisionId);
}
