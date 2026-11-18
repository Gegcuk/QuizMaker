package uk.gegc.quizmaker.features.result.application;

import java.time.Instant;
import java.util.UUID;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfiguration;

/** Authenticated author boundary. Publication does not execute or activate a release. */
public interface ReleaseConfigurationService {
    ReleaseConfiguration preview(UUID quizId, UUID revisionId, ReleaseConfiguration rule);
    Publication publish(Command command);
    Publication current(UUID quizId, UUID revisionId);
    Publication read(UUID quizId, UUID revisionId, UUID configurationId);

    record Command(UUID quizId, UUID revisionId, UUID commandId, long expectedVersion, ReleaseConfiguration rule) {
        @Override public String toString() { return "ReleaseConfigurationCommand"; }
    }
    record Publication(UUID id, UUID quizId, UUID revisionId, long publicationVersion,
                       String hash, ReleaseConfiguration rule, Instant publishedAt, boolean replayed) {
        @Override public String toString() { return "ReleaseConfigurationPublication"; }
    }
}
