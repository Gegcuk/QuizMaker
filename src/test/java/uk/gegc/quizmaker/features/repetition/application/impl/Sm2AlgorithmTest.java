package uk.gegc.quizmaker.features.repetition.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.repetition.application.SrsAlgorithm;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Sm2Algorithm Tests")
class Sm2AlgorithmTest extends BaseUnitTest {

    private Sm2Algorithm algorithm;
    private static final Instant NOW = Instant.parse("2025-02-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        algorithm = new Sm2Algorithm();
    }

    @Test
    @DisplayName("Should reset repetitionCount=0 and intervalDays=1 for AGAIN")
    void shouldResetOnAgain() {
        SrsAlgorithm.SchedulingResult result = algorithm.applyReview(2, 10, 2.5, RepetitionEntryGrade.AGAIN, NOW);

        assertEquals(0, result.repetitionCount());
        assertEquals(1, result.intervalDays());
    }

    @Test
    @DisplayName("Should return interval=1 when repCount=0")
    void shouldReturnInterval1ForFirstSuccess() {
        SrsAlgorithm.SchedulingResult result = algorithm.applyReview(0, 0, 2.5, RepetitionEntryGrade.GOOD, NOW);

        assertEquals(1, result.intervalDays());
        assertEquals(1, result.repetitionCount());
    }

    @Test
    @DisplayName("Should return interval=6 when repCount=1")
    void shouldReturnInterval6ForSecondSuccess() {
        SrsAlgorithm.SchedulingResult result = algorithm.applyReview(1, 1, 2.5, RepetitionEntryGrade.GOOD, NOW);

        assertEquals(6, result.intervalDays());
        assertEquals(2, result.repetitionCount());
    }

    @Test
    @DisplayName("Should use round(currentInterval*updatedEase) when repCount>=2")
    void shouldUseMultiplierForLaterReviews() {
        // repCount=2, currentInterval=6, GOOD -> updatedEase=2.5, interval = round(6*2.5)=15
        SrsAlgorithm.SchedulingResult result = algorithm.applyReview(2, 6, 2.5, RepetitionEntryGrade.GOOD, NOW);

        assertEquals(15, result.intervalDays());
        assertEquals(3, result.repetitionCount());
    }

    @Test
    @DisplayName("Should clamp ease factor to >= 1.3")
    void shouldClampEaseFactor() {
        // With currentEase=1.3 and AGAIN (q=0), formula gives < 1.3, so result is clamped to 1.3
        SrsAlgorithm.SchedulingResult result = algorithm.applyReview(1, 1, 1.3, RepetitionEntryGrade.AGAIN, NOW);

        assertTrue(result.easeFactor() >= 1.3, "ease factor should be clamped to >= 1.3");
        assertEquals(1.3, result.easeFactor(), 0.0001);
    }

    @Test
    @DisplayName("Should set nextReviewAt = now + intervalDays")
    void shouldSetNextReviewAt() {
        int intervalDays = 6;
        SrsAlgorithm.SchedulingResult result = algorithm.applyReview(1, 1, 2.5, RepetitionEntryGrade.GOOD, NOW);

        assertEquals(NOW.plus(intervalDays, ChronoUnit.DAYS), result.nextReviewAt());
        assertEquals(NOW, result.lastReviewedAt());
    }

    @Test
    @DisplayName("Should use default ease factor in initialSchedule")
    void shouldUseDefaultEaseFactorInInitialSchedule() {
        SrsAlgorithm.SchedulingResult result = algorithm.initialSchedule(RepetitionEntryGrade.GOOD, NOW);

        // GOOD with default 2.5 leaves ease at 2.5
        assertEquals(2.5, result.easeFactor(), 0.0001);
        assertEquals(1, result.intervalDays());
        assertEquals(1, result.repetitionCount());
    }
}