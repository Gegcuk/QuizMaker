package uk.gegc.quizmaker.features.repetition.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.repetition.application.*;
import uk.gegc.quizmaker.features.repetition.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@DisplayName("RepetitionProcessingServiceImpl Tests")
class RepetitionProcessingServiceImplTest extends BaseUnitTest {

    @Mock private uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository attemptRepository;
    @Mock private uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository logRepository;
    @Mock private RepetitionStrategyRegistry strategyRegistry;
    @Mock private AttemptContentResolver contentResolver;
    @Mock private RepetitionReviewLogFactory logFactory;
    @Mock private SrsAlgorithm srsAlgorithm;
    @Mock private RepetitionProcessingService self;
    @Mock private RepetitionContentStrategy strategy;

    private Clock clock;
    private RepetitionProcessingServiceImpl processingService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        processingService = new RepetitionProcessingServiceImpl(
                attemptRepository, logRepository, strategyRegistry, contentResolver,
                logFactory, srsAlgorithm, clock, self);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when attempt is missing")
    void shouldThrowWhenAttemptMissing() {
        UUID attemptId = UUID.randomUUID();
        when(attemptRepository.findFullyLoadedById(attemptId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> processingService.processAttempt(attemptId));

        verify(attemptRepository).findFullyLoadedById(attemptId);
        verifyNoInteractions(self);
    }

    @Test
    @DisplayName("Should skip processing when repetition is disabled")
    void shouldSkipWhenRepetitionDisabled() {
        UUID attemptId = UUID.randomUUID();
        Attempt attempt = mock(Attempt.class);
        Quiz quiz = mock(Quiz.class);
        when(quiz.getIsRepetitionEnabled()).thenReturn(false);
        when(attempt.getQuiz()).thenReturn(quiz);
        when(attemptRepository.findFullyLoadedById(attemptId)).thenReturn(Optional.of(attempt));

        processingService.processAttempt(attemptId);

        verify(attemptRepository).findFullyLoadedById(attemptId);
        verify(attempt).getQuiz();
        verifyNoInteractions(self);
    }

    @Test
    @DisplayName("Should map correct answer to GOOD and incorrect to AGAIN")
    void shouldMapAnswerGrades() {
        Answer correctAnswer = answerWithIsCorrect(true);
        Answer incorrectAnswer = answerWithIsCorrect(false);
        ContentKey key = new ContentKey(RepetitionContentType.QUESTION, UUID.randomUUID());
        SpacedRepetitionEntry entry = newEntry(0, 0);

        when(contentResolver.resolve(any())).thenReturn(key);
        when(strategyRegistry.get(RepetitionContentType.QUESTION)).thenReturn(strategy);
        when(strategy.findOrCreateEntry(any(), eq(key))).thenReturn(entry);
        ArgumentCaptor<RepetitionEntryGrade> gradeCaptor = ArgumentCaptor.forClass(RepetitionEntryGrade.class);
        when(srsAlgorithm.initialSchedule(gradeCaptor.capture(), any())).thenReturn(schedulingResult());

        processingService.processAnswerTx(correctAnswer);
        assertEquals(RepetitionEntryGrade.GOOD, gradeCaptor.getValue());

        when(srsAlgorithm.initialSchedule(gradeCaptor.capture(), any())).thenReturn(schedulingResult());
        processingService.processAnswerTx(incorrectAnswer);
        assertEquals(RepetitionEntryGrade.AGAIN, gradeCaptor.getValue());
    }

    @Test
    @DisplayName("Should use completedAt or fall back to Clock")
    void shouldUseCompletedAtOrClock() {
        Instant completedAt = Instant.parse("2025-01-15T12:00:00Z");
        Answer answerWithCompletedAt = answerWithCompletedAt(completedAt);
        Answer answerWithNullCompletedAt = answerWithCompletedAt(null);

        ContentKey key = new ContentKey(RepetitionContentType.QUESTION, UUID.randomUUID());
        SpacedRepetitionEntry entry = newEntry(0, 0);
        when(contentResolver.resolve(any())).thenReturn(key);
        when(strategyRegistry.get(RepetitionContentType.QUESTION)).thenReturn(strategy);
        when(strategy.findOrCreateEntry(any(), eq(key))).thenReturn(entry);

        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        when(srsAlgorithm.initialSchedule(any(), instantCaptor.capture())).thenReturn(schedulingResult());

        processingService.processAnswerTx(answerWithCompletedAt);
        assertEquals(completedAt, instantCaptor.getValue());

        when(srsAlgorithm.initialSchedule(any(), instantCaptor.capture())).thenReturn(schedulingResult());
        processingService.processAnswerTx(answerWithNullCompletedAt);
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), instantCaptor.getValue());
    }

    @Test
    @DisplayName("Should use initialSchedule only when repetitionCount=0 and intervalDays=0")
    void shouldUseInitialScheduleOnlyForNewEntry() {
        Answer answer = answerWithIsCorrect(true);
        ContentKey key = new ContentKey(RepetitionContentType.QUESTION, UUID.randomUUID());
        SpacedRepetitionEntry newEntry = newEntry(0, 0);

        when(contentResolver.resolve(any())).thenReturn(key);
        when(strategyRegistry.get(RepetitionContentType.QUESTION)).thenReturn(strategy);
        when(strategy.findOrCreateEntry(any(), eq(key))).thenReturn(newEntry);
        when(srsAlgorithm.initialSchedule(any(), any())).thenReturn(schedulingResult());

        processingService.processAnswerTx(answer);

        verify(srsAlgorithm).initialSchedule(any(), any());
        verify(srsAlgorithm, never()).applyReview(anyInt(), anyInt(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("Should use applyReview for existing entry")
    void shouldUseApplyReviewForExistingEntry() {
        Answer answer = answerWithIsCorrect(true);
        ContentKey key = new ContentKey(RepetitionContentType.QUESTION, UUID.randomUUID());
        SpacedRepetitionEntry existingEntry = newEntry(1, 6);
        existingEntry.setEaseFactor(2.5);

        when(contentResolver.resolve(any())).thenReturn(key);
        when(strategyRegistry.get(RepetitionContentType.QUESTION)).thenReturn(strategy);
        when(strategy.findOrCreateEntry(any(), eq(key))).thenReturn(existingEntry);
        when(srsAlgorithm.applyReview(eq(1), eq(6), eq(2.5), any(), any())).thenReturn(schedulingResult());

        processingService.processAnswerTx(answer);

        verify(srsAlgorithm).applyReview(eq(1), eq(6), eq(2.5), eq(RepetitionEntryGrade.GOOD), any());
        verify(srsAlgorithm, never()).initialSchedule(any(), any());
    }

    @Test
    @DisplayName("Should update entry and write log per answer")
    void shouldUpdateEntryAndWriteLog() {
        Answer answer = answerWithIsCorrect(true);
        ContentKey key = new ContentKey(RepetitionContentType.QUESTION, UUID.randomUUID());
        SpacedRepetitionEntry entry = newEntry(0, 0);
        SrsAlgorithm.SchedulingResult result = schedulingResult();
        RepetitionReviewLog log = new RepetitionReviewLog();

        when(contentResolver.resolve(any())).thenReturn(key);
        when(strategyRegistry.get(RepetitionContentType.QUESTION)).thenReturn(strategy);
        when(strategy.findOrCreateEntry(any(), eq(key))).thenReturn(entry);
        when(srsAlgorithm.initialSchedule(any(), any())).thenReturn(result);
        when(logFactory.fromAttempt(eq(answer), eq(entry), eq(key), eq(result))).thenReturn(log);

        processingService.processAnswerTx(answer);

        InOrder inOrder = inOrder(strategy, logRepository);
        inOrder.verify(strategy).applySchedule(entry, result);
        inOrder.verify(strategy).save(entry);
        inOrder.verify(logRepository).save(log);
    }

    @Test
    @DisplayName("Should swallow duplicate log insert and keep schedule idempotent")
    void shouldSwallowDuplicateLogInsert() {
        Attempt attempt = attemptWithOneAnswer();
        when(attemptRepository.findFullyLoadedById(attempt.getId())).thenReturn(Optional.of(attempt));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate entry"))
                .when(self).processAnswerTx(any(Answer.class));

        assertDoesNotThrow(() -> processingService.processAttempt(attempt.getId()));

        verify(self).processAnswerTx(any(Answer.class));
    }

    @Test
    @DisplayName("Should retry on OptimisticLockingFailureException and succeed")
    void shouldRetryOnOptimisticLock() {
        Attempt attempt = attemptWithOneAnswer();
        when(attemptRepository.findFullyLoadedById(attempt.getId())).thenReturn(Optional.of(attempt));
        doThrow(new org.springframework.dao.OptimisticLockingFailureException("conflict"))
                .doNothing()
                .when(self).processAnswerTx(any(Answer.class));

        processingService.processAttempt(attempt.getId());

        verify(self, times(2)).processAnswerTx(any(Answer.class));
    }

    @Test
    @DisplayName("Should retry on non-duplicate DataIntegrityViolationException and succeed")
    void shouldRetryOnNonDuplicateDataIntegrity() {
        Attempt attempt = attemptWithOneAnswer();
        when(attemptRepository.findFullyLoadedById(attempt.getId())).thenReturn(Optional.of(attempt));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Foreign key constraint"))
                .doNothing()
                .when(self).processAnswerTx(any(Answer.class));

        processingService.processAttempt(attempt.getId());

        verify(self, times(2)).processAnswerTx(any(Answer.class));
    }

    @Test
    @DisplayName("Should rethrow after max retries")
    void shouldRethrowAfterMaxRetries() {
        Attempt attempt = attemptWithOneAnswer();
        when(attemptRepository.findFullyLoadedById(attempt.getId())).thenReturn(Optional.of(attempt));
        doThrow(new org.springframework.dao.OptimisticLockingFailureException("conflict"))
                .when(self).processAnswerTx(any(Answer.class));

        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                () -> processingService.processAttempt(attempt.getId()));

        verify(self, times(3)).processAnswerTx(any(Answer.class));
    }

    // --- helpers ---

    private static SrsAlgorithm.SchedulingResult schedulingResult() {
        return new SrsAlgorithm.SchedulingResult(
                1, 1, 2.5,
                Instant.parse("2025-01-02T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                RepetitionEntryGrade.GOOD);
    }

    private static SpacedRepetitionEntry newEntry(int repetitionCount, int intervalDays) {
        SpacedRepetitionEntry e = new SpacedRepetitionEntry();
        e.setUser(mock(User.class));
        e.setQuestion(mock(uk.gegc.quizmaker.features.question.domain.model.Question.class));
        e.setRepetitionCount(repetitionCount);
        e.setIntervalDays(intervalDays);
        e.setEaseFactor(2.5);
        e.setNextReviewAt(Instant.now());
        return e;
    }

    private Answer answerWithIsCorrect(boolean correct) {
        Answer a = mock(Answer.class);
        Attempt att = mock(Attempt.class);
        lenient().when(att.getUser()).thenReturn(mock(User.class));
        lenient().when(att.getCompletedAt()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(a.getAttempt()).thenReturn(att);
        lenient().when(a.getIsCorrect()).thenReturn(correct);
        lenient().when(a.getQuestion()).thenReturn(mock(uk.gegc.quizmaker.features.question.domain.model.Question.class));
        return a;
    }

    private Answer answerWithCompletedAt(Instant completedAt) {
        Answer a = mock(Answer.class);
        Attempt att = mock(Attempt.class);
        lenient().when(att.getUser()).thenReturn(mock(User.class));
        lenient().when(att.getCompletedAt()).thenReturn(completedAt);
        lenient().when(a.getAttempt()).thenReturn(att);
        lenient().when(a.getIsCorrect()).thenReturn(true);
        lenient().when(a.getQuestion()).thenReturn(mock(uk.gegc.quizmaker.features.question.domain.model.Question.class));
        return a;
    }

    private Attempt attemptWithOneAnswer() {
        Attempt attempt = mock(Attempt.class);
        Quiz quiz = mock(Quiz.class);
        Answer answer = answerWithIsCorrect(true);
        when(attempt.getId()).thenReturn(UUID.randomUUID());
        when(attempt.getQuiz()).thenReturn(quiz);
        when(quiz.getIsRepetitionEnabled()).thenReturn(true);
        when(attempt.getAnswers()).thenReturn(List.of(answer));
        return attempt;
    }
}
