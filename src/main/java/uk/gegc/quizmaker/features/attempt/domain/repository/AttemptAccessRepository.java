package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;

import java.util.Optional;
import java.util.UUID;

/** Current, bounded metadata reads only. Resource/operation authorization belongs to the service. */
public interface AttemptAccessRepository {
    Optional<Evidence> lockEvidence(UUID attemptId, UUID trustedUserId);

    record Evidence(UUID quizId, UUID contentOwnerId, UUID revisionId, UUID formId,
                    UUID policySnapshotId, LifecycleState lifecycle) {
        @Override public String toString() { return "AttemptAccessEvidence"; }
    }
}
