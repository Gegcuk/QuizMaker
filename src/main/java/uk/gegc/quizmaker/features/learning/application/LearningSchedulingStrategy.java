package uk.gegc.quizmaker.features.learning.application;

import java.time.Instant;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;

/**
 * Dark deterministic FSRS calculation boundary. Callers resolve current learner/content access,
 * explicit enrollment, captured revision and cycle before calling. No method reads a system clock,
 * writes a schedule, deduplicates durable review IDs or grants cross-user access. #649/#650 own
 * those commands and their transactions; replay the same captured input, not an evolved state.
 */
public interface LearningSchedulingStrategy {
    State initial(Identity identity, FsrsManifest manifest, Instant startedAt);
    Transition review(State current, Review review);
}
