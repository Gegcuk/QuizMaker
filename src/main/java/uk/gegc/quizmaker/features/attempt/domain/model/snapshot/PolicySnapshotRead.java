package uk.gegc.quizmaker.features.attempt.domain.model.snapshot;

public sealed interface PolicySnapshotRead permits PolicySnapshot, PolicySnapshotRead.Legacy {
    enum Legacy implements PolicySnapshotRead { NO_SNAPSHOT }
}
