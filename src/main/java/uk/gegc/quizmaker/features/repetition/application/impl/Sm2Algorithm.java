package uk.gegc.quizmaker.features.repetition.application.impl;

import uk.gegc.quizmaker.features.repetition.application.SrsAlgorithm;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class Sm2Algorithm implements SrsAlgorithm {

    private static final double MIN_EASE_FACTOR = 1.3;
    private static final double DEFAULT_EASE_FACTOR = 2.5;
    private static final int AGAIN_INTERVAL_DAYS = 1;
    private static final int FIRST_SUCCESS_INTERVAL_DAYS = 1;
    private static final int SECOND_SUCCESS_INTERVAL_DAYS = 6;

    @Override
    public SchedulingResult applyReview(
            int currentRepetitionCount,
            int currentIntervalDays,
            double currentEaseFactor,
            RepetitionEntryGrade grade,
            Instant now
    ) {
        int qValue = grade.getSm2Value();
        double updatedEase = calculateUpdatedEase(currentEaseFactor, qValue);

        int repetitionCount;
        int intervalDays;

        if (qValue < 3) {
            repetitionCount = 0;
            intervalDays = AGAIN_INTERVAL_DAYS;
        } else {
            intervalDays = computeIntervalDays(currentRepetitionCount, currentIntervalDays, updatedEase);
            repetitionCount = currentRepetitionCount + 1;
        }

        Instant nextReview = now.plus(intervalDays, ChronoUnit.DAYS);

        return new SchedulingResult(intervalDays, repetitionCount, updatedEase, nextReview, now, grade);
    }

    @Override
    public SchedulingResult initialSchedule(RepetitionEntryGrade grade, Instant now) {
        return applyReview(0, 0, DEFAULT_EASE_FACTOR, grade, now);
    }

    private int computeIntervalDays(int repetitionCount, int currentIntervalDays, double updatedEase) {
        if (repetitionCount == 0) return FIRST_SUCCESS_INTERVAL_DAYS;
        if (repetitionCount == 1) return SECOND_SUCCESS_INTERVAL_DAYS;
        return (int) Math.round(currentIntervalDays * updatedEase);
    }

    private double calculateUpdatedEase(double currentEaseFactor, int qValue) {
        double updatedEase = currentEaseFactor
                + (0.1 - (5 - qValue) * (0.08 + (5 - qValue) * 0.02));
        return Math.max(updatedEase, MIN_EASE_FACTOR);
    }
}