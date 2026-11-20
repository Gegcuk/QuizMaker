package uk.gegc.quizmaker.features.learning.domain.repository;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Admission for one logical mutation, after authorization and in its owning writable RC transaction. */
public interface LearningCommandBudget {
    /** Serialize a new debit before domain writes. The callback checks durable identity in
     * the caller's RC transaction. Any subsequent rejection or failed write rolls back the debit. */
    void chargeIfNew(UUID trustedLearnerId, BooleanSupplier recorded);
}
