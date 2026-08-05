package uk.gegc.quizmaker.features.auth.infra.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Claims that have passed signature, expiry, password-version, and purpose checks.
 */
public record ValidatedJwt(
        String username,
        UUID userId,
        UUID sessionId,
        Instant expiresAt
) {
}
