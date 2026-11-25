package uk.gegc.quizmaker.features.learning.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningHistorySelection.Batch;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/** Real immutable assessment facts; the caller supplies its authenticated, authorized learner. */
public interface MistakeHistorySource {
    Batch mistakes(UUID learner, Source source, int afterPosition, int take);
}
