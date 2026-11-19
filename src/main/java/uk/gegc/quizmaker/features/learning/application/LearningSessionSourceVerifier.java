package uk.gegc.quizmaker.features.learning.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/**
 * Current Learning content authority inside the caller's transaction, including exact replays.
 * Verify access, visibility, release and applicable organization/tenant membership for the trusted
 * learner and exact immutable source; retain necessary authority protection through commit. This
 * does not grant assessment permissions or require the unused legacy repetition-enable flag.
 * Missing/ambiguous implementations deny. #575 tests use a source contract fake; #578/#692/#667
 * must supply and prove the real access integration before exposing Learning commands.
 */
public interface LearningSessionSourceVerifier {
    boolean allows(UUID trustedLearnerId, Source source);
}
