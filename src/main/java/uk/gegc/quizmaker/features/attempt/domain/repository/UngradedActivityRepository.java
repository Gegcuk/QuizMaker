package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.StartFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.UngradedPolicy;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;

/** Insert-only evidence. Writers hold the canonical attempt lock; the caller owns authorization. */
public interface UngradedActivityRepository {
    record Binding(UUID attemptId, UUID formId, UngradedPolicy policy, StartFacts startFacts) {
        @Override public String toString() { return "UngradedActivityBinding[redacted]"; }
    }

    record Participation(UUID id, Binding binding, UUID formItemId, UUID questionRevisionId,
                         ContractVersion responseContract, String normalizedResponse,
                         UUID commandId, Instant acceptedAt) {
        @Override public String toString() { return "UngradedParticipation[redacted]"; }
    }

    /** Nonlocking dispatch hint; each operation revalidates ownership, kind and authority. */
    boolean isOwnedUngraded(UUID attemptId, UUID trustedUserId);
    void bind(Binding binding);
    Optional<Binding> read(UUID attemptId, UUID formId);
    boolean participated(UUID attemptId, UUID formItemId);
    Set<UUID> participatedItems(UUID attemptId);
    void insert(Participation participation);
}
