package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotRead;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotReference;

import java.util.UUID;

/**
 * Dark internal boundary for authorized server callers. revisionOwnerId is trusted revision scope,
 * not a client identity or proof of participant access. #600 owns start/access and atomic binding.
 */
public interface PolicySnapshotService {
    /**
     * Exact replay reuses the immutable ID/value. Joins the caller transaction: later rollback
     * cancels a new insert. Use the returned value; an older REPEATABLE_READ snapshot may not see
     * another transaction's insert through an ordinary read even after successful replay.
     */
    PolicySnapshot createOrReplay(UUID revisionOwnerId, PolicySnapshot snapshot);

    /** Missing/foreign target references fail; only an explicit legacy reference returns legacy. */
    PolicySnapshotRead read(UUID revisionOwnerId, PolicySnapshotReference reference);
}
