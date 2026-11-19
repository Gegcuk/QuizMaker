package uk.gegc.quizmaker.features.learning.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

/**
 * Independent #575 aggregate, before form materialization. CREATED is not a started review,
 * enrollment or active schedule. #578/#650 own subsequent start/progress/completion commands;
 * those extensions must explicitly evolve this versioned storage contract.
 */
public record LearningSession(UUID id, int schemaVersion, UUID learnerId, UUID createCommandId,
                              Source source, FsrsManifest configuration, Status status, long version,
                              Instant createdAt, Instant updatedAt) {
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("+10000-01-01T00:00:00Z");
    public enum Status { CREATED }

    /** Immutable source metadata only; never a mutable quiz, question text or access grant. */
    public record Source(int version, UUID quizId, UUID quizRevisionId, int revisionSchemaVersion) {
        public Source {
            require(version == 1, UNSUPPORTED_VERSION);
            require(quizId != null && quizRevisionId != null, INVALID_INPUT);
            require(revisionSchemaVersion >= 1 && revisionSchemaVersion <= 3, UNSUPPORTED_VERSION);
        }
        @Override public String toString() { return "LearningSessionSource[version=" + version + "]"; }
    }

    public LearningSession {
        require(schemaVersion == 1, UNSUPPORTED_VERSION);
        require(id != null && learnerId != null && createCommandId != null && source != null
                && configuration != null && status == Status.CREATED && version == 1, CORRUPT);
        require(createdAt != null && updatedAt != null && createdAt.equals(updatedAt)
                && createdAt.equals(createdAt.truncatedTo(ChronoUnit.MICROS))
                && !createdAt.isBefore(MIN_TIME) && createdAt.isBefore(MAX_TIME), CORRUPT);
    }

    @Override public String toString() { return "LearningSession[version=" + version + ",status=" + status + "]"; }
}
