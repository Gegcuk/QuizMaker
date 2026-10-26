package uk.gegc.quizmaker.features.attempt.domain.model.snapshot;

import java.util.UUID;

/** The trusted engine binding must classify legacy explicitly, never from a missing target row. */
public sealed interface PolicySnapshotReference permits PolicySnapshotReference.Stored, PolicySnapshotReference.Legacy {
    record Stored(UUID id) implements PolicySnapshotReference {
        public Stored {
            if (id == null) throw new PolicySnapshotException(PolicySnapshotException.Reason.INVALID_INPUT);
        }
    }

    enum Legacy implements PolicySnapshotReference { NO_SNAPSHOT }
}
