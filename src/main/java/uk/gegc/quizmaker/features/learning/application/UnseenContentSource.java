package uk.gegc.quizmaker.features.learning.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningHistorySelection.Batch;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/** Exact content identity and absence of accepted assessment/Learning answers, never inferred views. */
public interface UnseenContentSource {
    Batch unseen(UUID learner, Source source, int afterPosition, int take);
}
