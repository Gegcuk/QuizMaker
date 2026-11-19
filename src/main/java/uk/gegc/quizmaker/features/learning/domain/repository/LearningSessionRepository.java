package uk.gegc.quizmaker.features.learning.domain.repository;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;

/** Persistence-only port; requires the authorized caller's transaction. No update or delete API. */
public interface LearningSessionRepository {
    boolean sourceExists(LearningSession.Source source);

    /** No-op duplicate insert serializes one learner/command; never overwrites the winning input. */
    LearningSession insertOrRead(LearningSession candidate);

    Optional<LearningSession> find(UUID trustedLearnerId, UUID sessionId);
    void recordCreation(LearningSession session);
    boolean hasCreationReceipt(LearningSession session);
}
