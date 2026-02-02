package uk.gegc.quizmaker.features.repetition.application;

import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;

import java.util.UUID;

public interface RepetitionReminderService {
    SpacedRepetitionEntry setReminderEnabled(UUID entryId, UUID userId, boolean enabled);
}
