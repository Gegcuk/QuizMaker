package uk.gegc.quizmaker.features.learning.application;

import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import java.util.List;

/**
 * One-way, participant-safe immutable source port, inside the caller's transaction. Resolve only
 * requested saved identities/contracts/options, in their saved order; never current quiz/due state.
 * Reject unknown contracts or unavailable material, retain current display authority through commit,
 * and sanitize failures before any nested transaction interceptor. No URLs, answers or author data.
 * #647 uses contract fixtures; #578/#692/#667 own real content/access integration and activation.
 * Missing/ambiguous providers deny; this foundation deliberately supplies no runtime adapter.
 */
public interface LearningFormContent {
    record PresentedItem(LearningForm.Item item, String questionText, ParticipantContent content) {
        @Override public String toString() { return "LearningPresentedItem[redacted]"; }
    }
    List<PresentedItem> read(LearningSession session, List<LearningForm.Item> savedItems);
}
