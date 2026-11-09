package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AttemptLimitPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;

public interface AttemptAllowanceRepository {
    void lock(Scope scope, AttemptLimitPolicy policy);
    Facts readCurrent(Scope scope, AttemptLimitPolicy policy);
    void bind(Scope scope, UUID attemptId, UUID startEventId);
}
