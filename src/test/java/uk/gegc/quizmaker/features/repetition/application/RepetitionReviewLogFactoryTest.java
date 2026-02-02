package uk.gegc.quizmaker.features.repetition.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.repetition.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@DisplayName("RepetitionReviewLogFactory Tests")
class RepetitionReviewLogFactoryTest extends BaseUnitTest {

    @Mock private Answer answer;
    @Mock private Attempt attempt;
    @Mock private SpacedRepetitionEntry entry;
    @Mock private User user;

    private RepetitionReviewLogFactory factory;

    @BeforeEach
    void setUp() {
        factory = new RepetitionReviewLogFactory();
    }

    @Test
    @DisplayName("Should set user, entry, contentType/id, grade, reviewedAt")
    void shouldSetCoreFields() {
        UUID contentId = UUID.randomUUID();
        ContentKey key = new ContentKey(RepetitionContentType.QUESTION, contentId);
        Instant reviewedAt = Instant.parse("2025-01-01T12:00:00Z");
        SrsAlgorithm.SchedulingResult result = new SrsAlgorithm.SchedulingResult(
                1, 1, 2.5, Instant.now(), reviewedAt, RepetitionEntryGrade.GOOD);

        when(entry.getUser()).thenReturn(user);
        when(answer.getAttempt()).thenReturn(attempt);
        when(attempt.getId()).thenReturn(UUID.randomUUID());

        RepetitionReviewLog log = factory.fromAttempt(answer, entry, key, result);

        assertSame(user, log.getUser());
        assertSame(entry, log.getEntry());
        assertEquals(RepetitionContentType.QUESTION, log.getContentType());
        assertEquals(contentId, log.getContentId());
        assertEquals(RepetitionEntryGrade.GOOD, log.getGrade());
        assertEquals(reviewedAt, log.getReviewedAt());
    }

    @Test
    @DisplayName("Should set intervalDays, easeFactor, repetitionCount")
    void shouldSetSnapshotFields() {
        ContentKey key = new ContentKey(RepetitionContentType.QUESTION, UUID.randomUUID());
        int intervalDays = 6;
        double easeFactor = 2.48;
        int repetitionCount = 2;
        SrsAlgorithm.SchedulingResult result = new SrsAlgorithm.SchedulingResult(
                intervalDays, repetitionCount, easeFactor,
                Instant.now(), Instant.now(), RepetitionEntryGrade.GOOD);

        when(entry.getUser()).thenReturn(user);
        when(answer.getAttempt()).thenReturn(attempt);
        when(attempt.getId()).thenReturn(UUID.randomUUID());

        RepetitionReviewLog log = factory.fromAttempt(answer, entry, key, result);

        assertEquals(intervalDays, log.getIntervalDays());
        assertEquals(easeFactor, log.getEaseFactor());
        assertEquals(repetitionCount, log.getRepetitionCount());
    }

    @Test
    @DisplayName("Should set sourceType=ATTEMPT_ANSWER, sourceId=answer.id, attemptId=attempt.id")
    void shouldSetSourceFields() {
        UUID answerId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        ContentKey key = new ContentKey(RepetitionContentType.QUESTION, UUID.randomUUID());
        SrsAlgorithm.SchedulingResult result = new SrsAlgorithm.SchedulingResult(
                1, 1, 2.5, Instant.now(), Instant.now(), RepetitionEntryGrade.GOOD);

        when(entry.getUser()).thenReturn(user);
        when(answer.getId()).thenReturn(answerId);
        when(answer.getAttempt()).thenReturn(attempt);
        when(attempt.getId()).thenReturn(attemptId);

        RepetitionReviewLog log = factory.fromAttempt(answer, entry, key, result);

        assertEquals(RepetitionReviewSourceType.ATTEMPT_ANSWER, log.getSourceType());
        assertEquals(answerId, log.getSourceId());
        assertEquals(attemptId, log.getAttemptId());
    }
}
