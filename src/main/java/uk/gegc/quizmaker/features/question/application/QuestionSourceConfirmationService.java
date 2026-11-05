package uk.gegc.quizmaker.features.question.application;

import java.util.Set;
import java.util.UUID;

/** Internal moderation operation, after externally verifying legacy provenance; no HTTP endpoint. */
public interface QuestionSourceConfirmationService {
    /** Atomic, at most 100 questions. Same owner is idempotent; an existing different owner cannot be replaced. */
    void confirm(Set<UUID> questionIds, UUID verifiedOwnerId, String evidence);
}
