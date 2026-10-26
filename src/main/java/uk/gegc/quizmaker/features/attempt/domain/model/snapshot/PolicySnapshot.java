package uk.gegc.quizmaker.features.attempt.domain.model.snapshot;

import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolution;

import java.util.UUID;

/** Internal evidence only: identity and hash confer no participant/resource authority. */
public record PolicySnapshot(UUID id, PolicyResolution resolution) implements PolicySnapshotRead {
    public static final int SCHEMA_VERSION = 1;

    public PolicySnapshot {
        if (id == null || resolution == null) {
            throw new PolicySnapshotException(PolicySnapshotException.Reason.INVALID_INPUT);
        }
    }
}
