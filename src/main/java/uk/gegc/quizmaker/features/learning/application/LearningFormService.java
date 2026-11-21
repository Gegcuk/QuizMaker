package uk.gegc.quizmaker.features.learning.application;

import uk.gegc.quizmaker.features.learning.domain.model.LearningForm;
import uk.gegc.quizmaker.features.learning.domain.model.LearningReview;
import java.util.List;
import java.util.UUID;

/** Dark, authenticated session-owner boundary. Standalone/participating operations require writable RC.
 * Selection is supplied by a trusted later caller; this service never selects or advances a schedule.
 * Retry the same session and selection after uncertain commit; outer returns are provisional. */
public interface LearningFormService {
    record Materialize(int version, UUID sessionId, List<LearningForm.Item> items) {
        @Override public String toString() { return "LearningMaterialize[version=" + version + "]"; }
    }
    record View(LearningForm form, List<LearningFormContent.PresentedItem> items,
                LearningReview.Progress progress, LearningReview.Mode mode) {
        public View { items = List.copyOf(items); }
        public View(LearningForm form, List<LearningFormContent.PresentedItem> items) {
            this(form, items, new LearningReview.Progress(0, form.items().isEmpty() ? null : form.items().get(0).sourceItemId()),
                    LearningReview.Mode.DUE);
        }
        @Override public String toString() { return "LearningFormView[redacted]"; }
    }
    record Result(View view, boolean replayed) { }
    Result materialize(Materialize command);
    View read(UUID sessionId);
}
