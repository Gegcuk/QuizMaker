package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.UUID;

/** Bounded identity classification only; never authorizes access or reconstructs target evidence. */
public interface AttemptViewRouteRepository {
    boolean isOwnedLegacyOnly(UUID attemptId, UUID trustedUserId);
}
