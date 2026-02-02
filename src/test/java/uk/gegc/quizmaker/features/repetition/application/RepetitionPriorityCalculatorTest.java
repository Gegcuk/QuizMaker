package uk.gegc.quizmaker.features.repetition.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RepetitionPriorityCalculator Tests")
class RepetitionPriorityCalculatorTest extends BaseUnitTest {

    private RepetitionPriorityCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RepetitionPriorityCalculator();
    }

    @Test
    @DisplayName("Should return 0 when nextReviewAt is null")
    void shouldReturnZeroWhenNextReviewAtNull() {
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setNextReviewAt(null);
        entry.setLastGrade(RepetitionEntryGrade.GOOD);

        int score = calculator.compute(entry, Instant.now());

        assertEquals(0, score);
    }

    @Test
    @DisplayName("Should treat future nextReviewAt as overdueDays=0")
    void shouldTreatFutureAsNotOverdue() {
        Instant now = Instant.parse("2025-01-10T12:00:00Z");
        Instant futureReview = Instant.parse("2025-01-15T12:00:00Z");
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setNextReviewAt(futureReview);
        entry.setLastGrade(RepetitionEntryGrade.GOOD);

        int score = calculator.compute(entry, now);

        // Base 20 + 0 overdue days + GOOD weight 10 = 30
        assertEquals(30, score);
    }

    @Test
    @DisplayName("Should map grade weights for AGAIN/HARD/GOOD/EASY")
    void shouldMapGradeWeights() {
        Instant now = Instant.parse("2025-01-10T12:00:00Z");
        Instant dueNow = Instant.parse("2025-01-10T12:00:00Z");

        SpacedRepetitionEntry againEntry = entry(dueNow, RepetitionEntryGrade.AGAIN);
        SpacedRepetitionEntry hardEntry = entry(dueNow, RepetitionEntryGrade.HARD);
        SpacedRepetitionEntry goodEntry = entry(dueNow, RepetitionEntryGrade.GOOD);
        SpacedRepetitionEntry easyEntry = entry(dueNow, RepetitionEntryGrade.EASY);

        assertEquals(50, calculator.compute(againEntry, now));  // 20 + 0 + 30
        assertEquals(40, calculator.compute(hardEntry, now));   // 20 + 0 + 20
        assertEquals(30, calculator.compute(goodEntry, now));    // 20 + 0 + 10
        assertEquals(20, calculator.compute(easyEntry, now));   // 20 + 0 + 0
    }

    @Test
    @DisplayName("Should cap score at 100")
    void shouldCapScoreAt100() {
        Instant now = Instant.parse("2025-01-30T12:00:00Z");
        Instant longOverdue = Instant.parse("2025-01-01T12:00:00Z"); // 29 days overdue
        SpacedRepetitionEntry entry = entry(longOverdue, RepetitionEntryGrade.AGAIN);

        int score = calculator.compute(entry, now);

        // 20 + (29 * 5) + 30 = 20 + 145 + 30 = 195, capped to 100
        assertEquals(100, score);
    }

    @Test
    @DisplayName("Should treat null grade weight as 0 (base score still applies)")
    void shouldTreatNullGradeWeightAsZero() {
        Instant now = Instant.parse("2025-01-10T12:00:00Z");
        Instant dueNow = Instant.parse("2025-01-10T12:00:00Z");
        SpacedRepetitionEntry entry = entry(dueNow, null);

        int score = calculator.compute(entry, now);

        // Base 20 + 0 overdue + 0 grade weight = 20 (null grade adds 0)
        assertEquals(20, score);
    }

    private static SpacedRepetitionEntry entry(Instant nextReviewAt, RepetitionEntryGrade lastGrade) {
        SpacedRepetitionEntry e = new SpacedRepetitionEntry();
        e.setNextReviewAt(nextReviewAt);
        e.setLastGrade(lastGrade);
        return e;
    }
}
