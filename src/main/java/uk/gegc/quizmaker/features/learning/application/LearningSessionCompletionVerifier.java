package uk.gegc.quizmaker.features.learning.application;

import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;

/** #650 must prove authoritative session completion in this transaction before releasing a cycle.
 * No implementation means deny. A #649 contract fixture is not public session-completion proof. */
public interface LearningSessionCompletionVerifier {
    boolean completed(LearningSession session);
}
