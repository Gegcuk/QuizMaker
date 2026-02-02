package uk.gegc.quizmaker.features.repetition.application;

import uk.gegc.quizmaker.features.repetition.domain.model.ContentKey;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.user.domain.model.User;

public interface RepetitionContentStrategy {
    RepetitionContentType supportedType();
    SpacedRepetitionEntry findOrCreateEntry(User user, ContentKey key);
    void applySchedule(SpacedRepetitionEntry entry, SrsAlgorithm.SchedulingResult result);
    SpacedRepetitionEntry save(SpacedRepetitionEntry entry);
}
