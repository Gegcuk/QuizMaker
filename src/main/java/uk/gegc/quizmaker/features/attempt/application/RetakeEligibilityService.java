package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.Context;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.Decision;

/**
 * Internal read/decision port for current, verified immutable subject/context/policy/history facts.
 * Never bind Context from a participant request. The caller resolves authority and supplies a
 * complete scoped snapshot; unavailable/legacy/unknown facts fail closed. No client time or
 * claimed count is accepted. The result is advisory: #549 revalidates in the start transaction.
 * No repository, controller or existing start path is wired to this dark foundation.
 */
public interface RetakeEligibilityService {
    Decision evaluate(Context context);
}
