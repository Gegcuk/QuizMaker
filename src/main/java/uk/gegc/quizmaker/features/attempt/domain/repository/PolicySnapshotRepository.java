package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.Optional;
import java.util.UUID;

public interface PolicySnapshotRepository {
    /** Current locking read after immutable insert/no-op; participates in the caller transaction. */
    StoredSnapshot createOrReplay(UUID revisionOwnerId, StoredSnapshot snapshot);

    Optional<StoredSnapshot> findById(UUID revisionOwnerId, UUID snapshotId);

    /** Untrusted storage representation: the application boundary verifies every read. */
    record StoredSnapshot(UUID id, int schemaVersion, UUID quizRevisionId, int resolverVersion,
                          int canonicalVersion, String hashAlgorithm, String canonicalJson, String hash) { }
}
