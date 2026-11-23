package uk.gegc.quizmaker.features.learning.domain.repository;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Admission for one logical mutation, after authorization and in its owning writable RC transaction. */
public interface LearningCommandBudget {
    /** Serialize a new debit before domain writes. The callback checks durable identity in
     * the caller's RC transaction, before quota rejection both before and under the lock. It may
     * reject a conflicting command key; accepted replay returns true. Any subsequent rejection
     * or failed write rolls back the debit. */
    void chargeIfNew(UUID trustedLearnerId, BooleanSupplier recorded);

    /** Share the mutation lock before inspecting progress/schedules, without debit or refill. */
    void lockForInspection(UUID trustedLearnerId);
}
