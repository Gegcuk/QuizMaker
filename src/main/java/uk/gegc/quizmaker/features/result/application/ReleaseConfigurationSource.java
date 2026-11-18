package uk.gegc.quizmaker.features.result.application;

import java.util.UUID;

/** Current source/author authority retained through the caller's transaction; metadata only. */
public interface ReleaseConfigurationSource {
    UUID authorize(String principal, UUID quizId, UUID revisionId, boolean writing);
}
