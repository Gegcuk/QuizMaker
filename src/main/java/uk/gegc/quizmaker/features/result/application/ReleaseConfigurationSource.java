package uk.gegc.quizmaker.features.result.application;

import java.util.UUID;

/**
 * Current source/author authority retained through the caller's transaction; metadata only.
 * Providers discard driver diagnostics before their own transactional proxies see a failure;
 * an outer feature adapter cannot retroactively sanitize nested transaction logs.
 */
public interface ReleaseConfigurationSource {
    UUID authorize(String principal, UUID quizId, UUID revisionId, boolean writing);
}
