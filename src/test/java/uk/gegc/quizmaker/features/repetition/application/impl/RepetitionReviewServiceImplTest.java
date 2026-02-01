package uk.gegc.quizmaker.features.repetition.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReviewService;
import uk.gegc.quizmaker.features.repetition.application.SrsAlgorithm;
import uk.gegc.quizmaker.features.repetition.application.exception.RepetitionAlreadyProcessedException;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewLog;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewSourceType;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.repetition.domain.repository.RepetitionReviewLogRepository;
import uk.gegc.quizmaker.features.repetition.domain.repository.SpacedRepetitionEntryRepository;
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

@DisplayName("RepetitionReviewServiceImpl unit tests")
public class RepetitionReviewServiceImplTest extends BaseUnitTest {

    @Mock private SpacedRepetitionEntryRepository entryRepository;
    @Mock private RepetitionReviewLogRepository logRepository;
    @Mock private SrsAlgorithm srsAlgorithm;
    @Mock private RepetitionReviewService self;

    private Clock clock;
    private RepetitionReviewServiceImpl repetitionReviewService;

    @BeforeEach
    void setUp(){
        clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
        repetitionReviewService = new RepetitionReviewServiceImpl(clock, entryRepository, logRepository, srsAlgorithm, self);
    }

    @Test
    @DisplayName("reviewEntryTx: duplicate idempotency key throws RepetitionAlreadyProcessedException")
    void reviewEntryTx_duplicateIdempotencyKey_throwsAlreadyProcessed(){
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyId = UUID.randomUUID();

        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(1);
        entry.setEaseFactor(2.5);

        when(entryRepository.findByIdAndUser_Id(entryId,userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(
                        1,
                        0,
                        2.3,
                        Instant.now(clock),
                        Instant.now(clock),
                        RepetitionEntryGrade.AGAIN));

        doThrow(new DataIntegrityViolationException("Duplicate entry"))
                .when(logRepository).save(any());

        assertThrows(RepetitionAlreadyProcessedException.class, () ->
                repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.AGAIN, idempotencyId));
    }

    @Test
    @DisplayName("reviewEntryTx: entry not found throws ResourceNotFoundException")
    void reviewEntryTx_entryNotFound_throwsResourceNotFound() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, UUID.randomUUID()));
    }

    @Test
    @DisplayName("reviewEntryTx: uses Clock for reviewedAt passed into algorithm")
    void reviewEntryTx_usesClockForReviewedAt() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));

        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), instantCaptor.capture()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.parse("2025-01-02T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"), RepetitionEntryGrade.GOOD));

        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, UUID.randomUUID());

        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), instantCaptor.getValue());
    }

    @Test
    @DisplayName("reviewEntryTx: passes current entry values into SM-2 algorithm")
    void reviewEntryTx_passesCurrentValuesToAlgorithm() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        int intervalDays = 3;
        int repetitionCount = 2;
        double easeFactor = 2.4;
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(intervalDays);
        entry.setRepetitionCount(repetitionCount);
        entry.setEaseFactor(easeFactor);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));

        ArgumentCaptor<Integer> repCountCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> intervalCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Double> easeCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<RepetitionEntryGrade> gradeCaptor = ArgumentCaptor.forClass(RepetitionEntryGrade.class);
        when(srsAlgorithm.applyReview(repCountCaptor.capture(), intervalCaptor.capture(), easeCaptor.capture(), gradeCaptor.capture(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(6, 3, 2.5, Instant.now(clock), Instant.now(clock), RepetitionEntryGrade.GOOD));

        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.HARD, UUID.randomUUID());

        assertEquals(repetitionCount, repCountCaptor.getValue());
        assertEquals(intervalDays, intervalCaptor.getValue());
        assertEquals(easeFactor, easeCaptor.getValue());
        assertEquals(RepetitionEntryGrade.HARD, gradeCaptor.getValue());
    }

    @Test
    @DisplayName("reviewEntryTx: applies SchedulingResult to entry fields")
    void reviewEntryTx_appliesScheduleToEntry() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));

        int newInterval = 6;
        int newRepCount = 1;
        double newEase = 2.48;
        Instant nextReview = Instant.parse("2025-01-07T00:00:00Z");
        Instant lastReviewed = Instant.parse("2025-01-01T00:00:00Z");
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(newInterval, newRepCount, newEase, nextReview, lastReviewed, RepetitionEntryGrade.GOOD));

        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, UUID.randomUUID());

        assertEquals(newInterval, entry.getIntervalDays());
        assertEquals(newRepCount, entry.getRepetitionCount());
        assertEquals(newEase, entry.getEaseFactor());
        assertEquals(nextReview, entry.getNextReviewAt());
        assertEquals(lastReviewed, entry.getLastReviewedAt());
        assertEquals(RepetitionEntryGrade.GOOD, entry.getLastGrade());
    }

    @Test
    @DisplayName("reviewEntryTx: saves entry before log insert")
    void reviewEntryTx_savesEntryBeforeLog() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.now(clock), Instant.now(clock), RepetitionEntryGrade.GOOD));

        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, UUID.randomUUID());

        InOrder inOrder = inOrder(entryRepository, logRepository);
        inOrder.verify(entryRepository).save(entry);
        inOrder.verify(logRepository).save(any(RepetitionReviewLog.class));
    }

    @Test
    @DisplayName("reviewEntryTx: saves log with MANUAL_REVIEW, QUESTION, and contentId")
    void reviewEntryTx_savesLogWithCorrectFields() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        Question question = mock(Question.class);
        when(question.getId()).thenReturn(questionId);
        entry.setUser(mock(User.class));
        entry.setQuestion(question);
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.now(clock), Instant.now(clock), RepetitionEntryGrade.GOOD));

        ArgumentCaptor<RepetitionReviewLog> logCaptor = ArgumentCaptor.forClass(RepetitionReviewLog.class);
        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, UUID.randomUUID());
        verify(logRepository).save(logCaptor.capture());

        RepetitionReviewLog log = logCaptor.getValue();
        assertEquals(RepetitionReviewSourceType.MANUAL_REVIEW, log.getSourceType());
        assertEquals(RepetitionContentType.QUESTION, log.getContentType());
        assertEquals(questionId, log.getContentId());
    }

    @Test
    @DisplayName("reviewEntryTx: idempotencyKey stored as sourceId")
    void reviewEntryTx_storesIdempotencyKeyAsSourceId() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.now(clock), Instant.now(clock), RepetitionEntryGrade.GOOD));

        ArgumentCaptor<RepetitionReviewLog> logCaptor = ArgumentCaptor.forClass(RepetitionReviewLog.class);
        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey);
        verify(logRepository).save(logCaptor.capture());

        assertEquals(idempotencyKey, logCaptor.getValue().getSourceId());
    }

    @Test
    @DisplayName("reviewEntryTx: null idempotencyKey allows multiple logs")
    void reviewEntryTx_nullIdempotencyKey_allowsMultipleLogs() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.now(clock), Instant.now(clock), RepetitionEntryGrade.GOOD));

        ArgumentCaptor<RepetitionReviewLog> logCaptor = ArgumentCaptor.forClass(RepetitionReviewLog.class);

        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, null);
        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.HARD, null);

        verify(logRepository, times(2)).save(logCaptor.capture());
        List<RepetitionReviewLog> logs = logCaptor.getAllValues();
        assertEquals(2, logs.size());
        assertNull(logs.get(0).getSourceId());
        assertNull(logs.get(1).getSourceId());
    }

    @Test
    @DisplayName("reviewEntryTx: duplicate key with null idempotencyKey rethrows DataIntegrityViolationException")
    void reviewEntryTx_duplicateKeyWithNullIdempotencyKey_rethrows() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.now(clock), Instant.now(clock), RepetitionEntryGrade.GOOD));
        doThrow(new DataIntegrityViolationException("Duplicate entry")).when(logRepository).save(any());

        assertThrows(DataIntegrityViolationException.class, () ->
                repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, null));
    }

    @Test
    @DisplayName("reviewEntryTx: non-duplicate DataIntegrityViolationException is rethrown")
    void reviewEntryTx_nonDuplicateDataIntegrity_rethrows() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.now(clock), Instant.now(clock), RepetitionEntryGrade.GOOD));
        doThrow(new DataIntegrityViolationException("Foreign key constraint")).when(logRepository).save(any());

        assertThrows(DataIntegrityViolationException.class, () ->
                repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, UUID.randomUUID()));
    }

    @Test
    @DisplayName("reviewEntry: retries on OptimisticLockingFailureException and succeeds")
    void reviewEntry_retriesOnOptimisticLock_thenSucceeds() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        when(self.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey))
                .thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenReturn(entry);

        SpacedRepetitionEntry result = repetitionReviewService.reviewEntry(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey);

        assertSame(entry, result);
        verify(self, times(2)).reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey);
    }

    @Test
    @DisplayName("reviewEntry: after max retries, OptimisticLockingFailureException is thrown")
    void reviewEntry_exhaustsRetries_throwsOptimisticLock() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(self.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey))
                .thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThrows(OptimisticLockingFailureException.class, () ->
                repetitionReviewService.reviewEntry(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey));

        verify(self, times(3)).reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey);
    }

    @Test
    @DisplayName("reviewEntry: does not retry on RepetitionAlreadyProcessedException")
    void reviewEntry_doesNotRetryOnAlreadyProcessed() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(self.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey))
                .thenThrow(new RepetitionAlreadyProcessedException("already processed"));

        assertThrows(RepetitionAlreadyProcessedException.class, () ->
                repetitionReviewService.reviewEntry(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey));

        verify(self, times(1)).reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey);
    }

    @Test
    @DisplayName("reviewEntry: does not retry on ResourceNotFoundException")
    void reviewEntry_doesNotRetryOnResourceNotFound() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(self.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey))
                .thenThrow(new ResourceNotFoundException("Entry not found"));

        assertThrows(ResourceNotFoundException.class, () ->
                repetitionReviewService.reviewEntry(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey));

        verify(self, times(1)).reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey);
    }

    @Test
    @DisplayName("reviewEntry: does not retry on DataIntegrityViolationException")
    void reviewEntry_doesNotRetryOnDataIntegrityViolation() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        when(self.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey))
                .thenThrow(new DataIntegrityViolationException("constraint"));

        assertThrows(DataIntegrityViolationException.class, () ->
                repetitionReviewService.reviewEntry(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey));

        verify(self, times(1)).reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey);
    }

    @Test
    @DisplayName("reviewEntry: delegates to self.reviewEntryTx (proxy) with same parameters")
    void reviewEntry_delegatesToSelfTx() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RepetitionEntryGrade grade = RepetitionEntryGrade.EASY;
        UUID idempotencyKey = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        when(self.reviewEntryTx(entryId, userId, grade, idempotencyKey)).thenReturn(entry);

        repetitionReviewService.reviewEntry(entryId, userId, grade, idempotencyKey);

        verify(self).reviewEntryTx(entryId, userId, grade, idempotencyKey);
    }

    @Test
    @DisplayName("reviewEntry: returns the value from reviewEntryTx")
    void reviewEntry_returnsTxResult() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        when(self.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey)).thenReturn(entry);

        SpacedRepetitionEntry result = repetitionReviewService.reviewEntry(entryId, userId, RepetitionEntryGrade.GOOD, idempotencyKey);

        assertSame(entry, result);
    }

    @Test
    @DisplayName("Should set reviewedAt=Clock.now in log")
    void shouldSetReviewedAtInLog() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant clockNow = Instant.parse("2025-01-01T00:00:00Z");
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.now(clock), clockNow, RepetitionEntryGrade.GOOD));

        ArgumentCaptor<RepetitionReviewLog> logCaptor = ArgumentCaptor.forClass(RepetitionReviewLog.class);
        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, UUID.randomUUID());
        verify(logRepository).save(logCaptor.capture());

        assertEquals(clockNow, logCaptor.getValue().getReviewedAt());
    }

    @Test
    @DisplayName("Should preserve entry ownership (findByIdAndUser_Id)")
    void shouldEnforceOwnership() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SpacedRepetitionEntry entry = new SpacedRepetitionEntry();
        entry.setUser(mock(User.class));
        entry.setQuestion(mock(Question.class));
        entry.setIntervalDays(1);
        entry.setRepetitionCount(0);
        entry.setEaseFactor(2.5);
        when(entryRepository.findByIdAndUser_Id(entryId, userId)).thenReturn(Optional.of(entry));
        when(srsAlgorithm.applyReview(anyInt(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(new SrsAlgorithm.SchedulingResult(1, 1, 2.5, Instant.now(clock), Instant.now(clock), RepetitionEntryGrade.GOOD));

        repetitionReviewService.reviewEntryTx(entryId, userId, RepetitionEntryGrade.GOOD, UUID.randomUUID());

        verify(entryRepository).findByIdAndUser_Id(entryId, userId);
        verify(entryRepository, never()).findById(any());
    }

}
