package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Internal transactional storage; callers must verify every raw read through the application boundary. */
public interface LifecycleStateRepository {
    void insert(LifecycleState initial);
    Optional<StoredState> findById(UUID attemptId);
    Optional<StoredState> findByIdForUpdate(UUID attemptId);
    boolean compareAndSet(long expectedVersion, LifecycleState next);

    record StoredState(UUID attemptId, int lifecycleVersion, long rowVersion, String execution,
                       String answers, Instant createdAt, Instant updatedAt, Instant startedAt, Instant submittedAt) { }
}
