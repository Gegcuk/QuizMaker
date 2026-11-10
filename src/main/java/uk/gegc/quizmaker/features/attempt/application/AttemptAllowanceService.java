package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AttemptLimitPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.Scope;

/** Internal canonical-start collaborator. Requires the shared start transaction and verified access. */
public interface AttemptAllowanceService {
    /** Locks the exact scope until commit; reads current facts and freezes authorized configuration. */
    void check(Scope scope, AttemptLimitPolicy policy);
    /** Called after real lifecycle/form/trace writes, before receipt completion in the same transaction. */
    void bind(Scope scope, UUID attemptId, UUID startEventId);
}
