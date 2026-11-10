package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AttemptLimitPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;

public interface AttemptAllowanceRepository {
    void lock(Scope scope, AttemptLimitPolicy policy);
    Facts readCurrent(Scope scope, AttemptLimitPolicy policy);
    /** Current authoritative census: use on a rejecting path, or after this command's binding insert. */
    void verifyComplete(Scope scope);
    void bind(Scope scope, UUID attemptId, UUID startEventId);
}
