package uk.gegc.quizmaker.features.learning.application;

import uk.gegc.quizmaker.features.learning.domain.model.LearningDueSelection;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/** Dark self-scoped, online due selection for #578. No enrollment, capture, daily quota or writes.
 * Calls own/join explicit RC, including read-only RC. Time and learner are server-owned.
 * A later start must capture/revalidate the selected cycles and persist its chosen form/order;
 * paging observes current committed state and cannot freeze a session across concurrent reviews. */
public interface LearningDueSelectionService {
    record Select(int version, Source source, int limit, LearningDueSelection.Cursor after) {
        @Override public String toString() { return "SelectDueLearningItems[version=" + version + "]"; }
    }
    LearningDueSelection select(Select request);
}
