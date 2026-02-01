package uk.gegc.quizmaker.features.repetition.application;

import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;

import java.util.UUID;

public interface RepetitionReviewService {
    SpacedRepetitionEntry reviewEntry(UUID entryId, UUID userId, RepetitionEntryGrade grade, UUID idempotencyKey);
    SpacedRepetitionEntry reviewEntryTx(UUID entryId, UUID userId, RepetitionEntryGrade grade, UUID idempotencyKey);
}
