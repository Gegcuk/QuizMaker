package uk.gegc.quizmaker.features.learning.application;

import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import java.util.List;
import java.util.UUID;

/**
 * One-way, participant-safe immutable source port, inside the caller's transaction. Resolve only
 * requested saved identities/contracts/options, in their saved order; never current quiz/due state.
 * Reject unknown contracts or unavailable material, retain current display authority through commit,
 * and sanitize failures before any nested transaction interceptor. No URLs, answers or author data.
 * The opt-in Learning session API provides the published-revision adapter. An absent or ambiguous provider denies access.
 */
public interface LearningFormContent {
    record PresentedItem(LearningForm.Item item, String questionText, ParticipantContent content) {
        @Override public String toString() { return "LearningPresentedItem[redacted]"; }
    }
    List<PresentedItem> read(LearningSession session, List<LearningForm.Item> savedItems);

    static List<UUID> optionIds(ParticipantContent value) {
        if (value instanceof ParticipantContent.Choices c) return c.options().stream().map(ParticipantContent.Option::id).toList();
        if (value instanceof ParticipantContent.Ordering c) return c.items().stream().map(ParticipantContent.Option::id).toList();
        if (value instanceof ParticipantContent.FillGap c) return c.options().stream().map(ParticipantContent.Option::id).toList();
        if (value instanceof ParticipantContent.Matching c) return java.util.stream.Stream.concat(c.left().stream(), c.right().stream())
                .map(ParticipantContent.MatchingOption::id).toList();
        if (value instanceof ParticipantContent.Compliance c) return c.statements().stream().map(ParticipantContent.ComplianceStatement::id).toList();
        if (value instanceof ParticipantContent.Hotspot c) return c.regions().stream().map(ParticipantContent.HotspotRegion::id).toList();
        return List.of();
    }
}
