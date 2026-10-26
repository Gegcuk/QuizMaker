package uk.gegc.quizmaker.features.user.application;

import java.util.Optional;
import java.util.UUID;

/** Minimal current eligibility read. A lookup result alone grants no authentication or access. */
public interface AuthenticatedUserLookup {
    /** Exact username from a trusted principal, never a caller-selected user ID or email fallback. */
    Optional<UUID> findActiveUserId(String trustedUsername);
}
