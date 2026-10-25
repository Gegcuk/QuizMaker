package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;

/**
 * Pure, dark v1 resolution: no repositories, document content, clocks, random IDs or writes.
 * Callers must establish current authority and immutable revision existence before invoking it,
 * then revalidate at actual start. A result does not activate a strategy or permit an attempt.
 */
public interface AttemptPolicyResolver {
    PolicyResolution resolve(PolicyResolutionRequest request);
}
