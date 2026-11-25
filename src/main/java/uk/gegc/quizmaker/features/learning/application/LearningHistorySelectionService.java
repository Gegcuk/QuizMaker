package uk.gegc.quizmaker.features.learning.application;

import uk.gegc.quizmaker.features.learning.domain.model.LearningHistorySelection;
import uk.gegc.quizmaker.features.learning.domain.model.LearningHistorySelection.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/** Internal self-scoped online read. Does not activate new public session modes. */
public interface LearningHistorySelectionService {
    LearningHistorySelection select(Select request);

    record Select(int version, Source source, Mode mode, int limit, Cursor after) {
        @Override public String toString() { return "SelectLearningHistory[redacted]"; }
    }
}
