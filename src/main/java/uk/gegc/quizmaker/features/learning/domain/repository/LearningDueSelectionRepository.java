package uk.gegc.quizmaker.features.learning.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningDueSelection.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/** Requires authorized RC. Return at most take items in due/unsigned UUID order, with unknown
 * strategy/version failures explicit even when no supported item is currently due. No locks/writes. */
public interface LearningDueSelectionRepository {
    boolean sourceExists(Source source);
    List<Item> find(UUID trustedLearner, Source source, Instant now, Cursor after, int take);
    List<Item> findMixed(UUID learner, Source source, Instant now, int take);
}
