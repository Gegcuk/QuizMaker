package uk.gegc.quizmaker.features.repetition.application;

import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;

import java.time.Instant;

public interface SrsAlgorithm {

    SchedulingResult applyReview(
        int currentRepetitionCount,
        int currentIntervalDays,
        double currentEaseFactor,
        RepetitionEntryGrade grade,
        Instant now
    );

    SchedulingResult initialSchedule(
            RepetitionEntryGrade grade,
            Instant now
    );

    record SchedulingResult (
        int intervalDays,
        int repetitionCount,
        double easeFactor,
        Instant nextReviewAt,
        Instant lastReviewedAt,
        RepetitionEntryGrade lastGrade
        ){}
}
