package uk.gegc.quizmaker.features.result.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.attempt.domain.event.AttemptCompletedEvent;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.result.application.QuizAnalyticsService;
import uk.gegc.quizmaker.features.result.domain.model.QuizAnalyticsSnapshot;
import uk.gegc.quizmaker.features.result.domain.repository.QuizAnalyticsSnapshotRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("QuizAnalyticsServiceImpl Unit Tests")
class QuizAnalyticsServiceImplTest {

    @Mock
    private QuizAnalyticsSnapshotRepository snapshotRepository;
    @Mock
    private AttemptRepository attemptRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private QuizRepository quizRepository;
    @Mock
    private QuizAnalyticsService self;

    @InjectMocks
    private QuizAnalyticsServiceImpl service;

    private UUID quizId;
    private Quiz testQuiz;

    @BeforeEach
    void setUp() {
        quizId = UUID.randomUUID();
        testQuiz = new Quiz();
        testQuiz.setId(quizId);

        // Set maxAgeSeconds via reflection (default 600)
        ReflectionTestUtils.setField(service, "maxAgeSeconds", 600L);
    }

    // ============ recomputeSnapshot Tests ============

    // Note: Quiz existence is enforced by FK constraint on quiz_analytics_snapshot.quiz_id
    // No explicit quiz existence test needed - DB will enforce referential integrity

    @Test
    @DisplayName("recomputeSnapshot: zero attempts creates snapshot with zeros")
    void recomputeSnapshot_zeroAttempts_createsZeroSnapshot() {
        // Given
        when(attemptRepository.getAttemptAggregateData(quizId)).thenReturn(List.of());
        when(attemptRepository.findCompletedWithAnswersByQuizId(quizId)).thenReturn(List.of());
        when(questionRepository.countByQuizId_Id(quizId)).thenReturn(10L);

        QuizAnalyticsSnapshot savedSnapshot = new QuizAnalyticsSnapshot();
        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.empty());
        when(snapshotRepository.save(any(QuizAnalyticsSnapshot.class))).thenAnswer(invocation -> {
            QuizAnalyticsSnapshot s = invocation.getArgument(0);
            savedSnapshot.setQuizId(s.getQuizId());
            savedSnapshot.setAttemptsCount(s.getAttemptsCount());
            savedSnapshot.setAverageScore(s.getAverageScore());
            savedSnapshot.setBestScore(s.getBestScore());
            savedSnapshot.setWorstScore(s.getWorstScore());
            savedSnapshot.setPassRate(s.getPassRate());
            savedSnapshot.setUpdatedAt(s.getUpdatedAt());
            return savedSnapshot;
        });

        // When
        QuizAnalyticsSnapshot result = service.recomputeSnapshot(quizId);

        // Then
        assertThat(result.getQuizId()).isEqualTo(quizId);
        assertThat(result.getAttemptsCount()).isZero();
        assertThat(result.getAverageScore()).isZero();
        assertThat(result.getBestScore()).isZero();
        assertThat(result.getWorstScore()).isZero();
        assertThat(result.getPassRate()).isZero();

        verify(snapshotRepository).save(any(QuizAnalyticsSnapshot.class));
    }

    @Test
    @DisplayName("recomputeSnapshot: computes correct aggregates for multiple attempts")
    void recomputeSnapshot_multipleAttempts_computesCorrectly() {
        // Given
        // Aggregate data: count=3, avg=75.0, best=90.0, worst=60.0
        Object[] aggregateData = new Object[]{3L, 75.0, 90.0, 60.0};
        List<Object[]> aggregateList = new ArrayList<>();
        aggregateList.add(aggregateData);
        when(attemptRepository.getAttemptAggregateData(quizId)).thenReturn(aggregateList);

        // 3 completed attempts with 10 questions each
        when(questionRepository.countByQuizId_Id(quizId)).thenReturn(10L);

        // Attempt 1: 8/10 correct (80%, passing)
        Attempt attempt1 = createAttempt(quizId, 8, 2);
        // Attempt 2: 9/10 correct (90%, passing)
        Attempt attempt2 = createAttempt(quizId, 9, 1);
        // Attempt 3: 4/10 correct (40%, failing)
        Attempt attempt3 = createAttempt(quizId, 4, 6);

        when(attemptRepository.findCompletedWithAnswersByQuizId(quizId))
                .thenReturn(List.of(attempt1, attempt2, attempt3));

        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.empty());
        when(snapshotRepository.save(any(QuizAnalyticsSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        QuizAnalyticsSnapshot result = service.recomputeSnapshot(quizId);

        // Then
        assertThat(result.getAttemptsCount()).isEqualTo(3);
        assertThat(result.getAverageScore()).isEqualTo(75.0);
        assertThat(result.getBestScore()).isEqualTo(90.0);
        assertThat(result.getWorstScore()).isEqualTo(60.0);
        // Pass rate: 2 out of 3 passed (66.67%)
        assertThat(result.getPassRate()).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));

        verify(snapshotRepository).save(any(QuizAnalyticsSnapshot.class));
    }

    @Test
    @DisplayName("recomputeSnapshot: all incorrect answers results in 0% pass rate")
    void recomputeSnapshot_allIncorrect_zeroPassRate() {
        // Given
        Object[] aggregateData = new Object[]{2L, 10.0, 15.0, 5.0};
        List<Object[]> aggregateList = new ArrayList<>();
        aggregateList.add(aggregateData);
        when(attemptRepository.getAttemptAggregateData(quizId)).thenReturn(aggregateList);
        when(questionRepository.countByQuizId_Id(quizId)).thenReturn(10L);

        // Both attempts have < 50% correct
        Attempt attempt1 = createAttempt(quizId, 2, 8); // 20%
        Attempt attempt2 = createAttempt(quizId, 3, 7); // 30%

        when(attemptRepository.findCompletedWithAnswersByQuizId(quizId))
                .thenReturn(List.of(attempt1, attempt2));

        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.empty());
        when(snapshotRepository.save(any(QuizAnalyticsSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        QuizAnalyticsSnapshot result = service.recomputeSnapshot(quizId);

        // Then
        assertThat(result.getPassRate()).isZero();
    }

    @Test
    @DisplayName("recomputeSnapshot: updates existing snapshot")
    void recomputeSnapshot_existingSnapshot_updates() {
        // Given
        Object[] aggregateData = new Object[]{1L, 85.0, 85.0, 85.0};
        List<Object[]> aggregateList = new ArrayList<>();
        aggregateList.add(aggregateData);
        when(attemptRepository.getAttemptAggregateData(quizId)).thenReturn(aggregateList);
        when(questionRepository.countByQuizId_Id(quizId)).thenReturn(10L);

        Attempt attempt = createAttempt(quizId, 8, 2);
        when(attemptRepository.findCompletedWithAnswersByQuizId(quizId)).thenReturn(List.of(attempt));

        // Existing snapshot
        QuizAnalyticsSnapshot existingSnapshot = new QuizAnalyticsSnapshot();
        existingSnapshot.setQuizId(quizId);
        existingSnapshot.setAttemptsCount(0);
        existingSnapshot.setAverageScore(0.0);
        existingSnapshot.setUpdatedAt(Instant.now().minus(1, ChronoUnit.HOURS));

        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.of(existingSnapshot));
        when(snapshotRepository.save(any(QuizAnalyticsSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        QuizAnalyticsSnapshot result = service.recomputeSnapshot(quizId);

        // Then
        assertThat(result.getQuizId()).isEqualTo(quizId);
        assertThat(result.getAttemptsCount()).isEqualTo(1); // Updated from 0
        assertThat(result.getAverageScore()).isEqualTo(85.0); // Updated from 0.0
        assertThat(result.getPassRate()).isEqualTo(100.0); // 1/1 passed

        verify(snapshotRepository).save(any(QuizAnalyticsSnapshot.class));
    }

    // ============ getOrComputeSnapshot Tests ============

    @Test
    @DisplayName("getOrComputeSnapshot: returns existing fresh snapshot without recompute")
    void getOrComputeSnapshot_freshSnapshot_returnsWithoutRecompute() {
        // Given
        QuizAnalyticsSnapshot freshSnapshot = new QuizAnalyticsSnapshot();
        freshSnapshot.setQuizId(quizId);
        freshSnapshot.setUpdatedAt(Instant.now()); // Fresh (just now)

        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.of(freshSnapshot));

        // When
        QuizAnalyticsSnapshot result = service.getOrComputeSnapshot(quizId);

        // Then
        assertThat(result).isEqualTo(freshSnapshot);
        verify(snapshotRepository).findByQuizId(quizId);
        verifyNoInteractions(self); // No recompute called
    }

    @Test
    @DisplayName("getOrComputeSnapshot: stale snapshot triggers recompute via proxy")
    void getOrComputeSnapshot_staleSnapshot_recomputes() {
        // Given
        QuizAnalyticsSnapshot staleSnapshot = new QuizAnalyticsSnapshot();
        staleSnapshot.setQuizId(quizId);
        staleSnapshot.setUpdatedAt(Instant.now().minus(15, ChronoUnit.MINUTES)); // Stale (>10 min)

        QuizAnalyticsSnapshot newSnapshot = new QuizAnalyticsSnapshot();
        newSnapshot.setQuizId(quizId);
        newSnapshot.setUpdatedAt(Instant.now());

        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.of(staleSnapshot));
        when(self.recomputeSnapshot(quizId)).thenReturn(newSnapshot);

        // When
        QuizAnalyticsSnapshot result = service.getOrComputeSnapshot(quizId);

        // Then
        assertThat(result).isEqualTo(newSnapshot);
        verify(self).recomputeSnapshot(quizId); // Called through proxy
    }

    @Test
    @DisplayName("getOrComputeSnapshot: missing snapshot triggers recompute via proxy")
    void getOrComputeSnapshot_missingSnapshot_recomputes() {
        // Given
        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.empty());

        QuizAnalyticsSnapshot newSnapshot = new QuizAnalyticsSnapshot();
        newSnapshot.setQuizId(quizId);
        when(self.recomputeSnapshot(quizId)).thenReturn(newSnapshot);

        // When
        QuizAnalyticsSnapshot result = service.getOrComputeSnapshot(quizId);

        // Then
        assertThat(result).isEqualTo(newSnapshot);
        verify(self).recomputeSnapshot(quizId);
    }

    @Test
    @DisplayName("getOrComputeSnapshot: staleness check disabled (maxAge=0) never recomputes")
    void getOrComputeSnapshot_stalenessDisabled_neverRecomputes() {
        // Given
        ReflectionTestUtils.setField(service, "maxAgeSeconds", 0L); // Disable staleness

        QuizAnalyticsSnapshot veryOldSnapshot = new QuizAnalyticsSnapshot();
        veryOldSnapshot.setQuizId(quizId);
        veryOldSnapshot.setUpdatedAt(Instant.now().minus(365, ChronoUnit.DAYS)); // 1 year old

        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.of(veryOldSnapshot));

        // When
        QuizAnalyticsSnapshot result = service.getOrComputeSnapshot(quizId);

        // Then
        assertThat(result).isEqualTo(veryOldSnapshot); // Returns old snapshot
        verifyNoInteractions(self); // No recompute
    }

    // ============ handleAttemptCompleted Tests ============

    @Test
    @DisplayName("handleAttemptCompleted: triggers recompute via proxy")
    void handleAttemptCompleted_triggersRecompute() {
        // Given
        UUID attemptId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AttemptCompletedEvent event = new AttemptCompletedEvent(
                this,
                attemptId,
                quizId,
                userId,
                Instant.now()
        );

        QuizAnalyticsSnapshot newSnapshot = new QuizAnalyticsSnapshot();
        when(self.recomputeSnapshot(quizId)).thenReturn(newSnapshot);

        // When
        service.handleAttemptCompleted(event);

        // Then
        verify(self).recomputeSnapshot(quizId); // Called through proxy
    }

    @Test
    @DisplayName("handleAttemptCompleted: error during recompute is caught and logged")
    void handleAttemptCompleted_errorDuringRecompute_isHandled() {
        // Given
        UUID attemptId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AttemptCompletedEvent event = new AttemptCompletedEvent(
                this,
                attemptId,
                quizId,
                userId,
                Instant.now()
        );

        when(self.recomputeSnapshot(quizId)).thenThrow(new RuntimeException("DB connection failed"));

        // When & Then - should not propagate exception
        service.handleAttemptCompleted(event);

        verify(self).recomputeSnapshot(quizId);
        // Exception is caught and logged, not rethrown
    }

    // ============ Edge Cases ============

    @Test
    @DisplayName("recomputeSnapshot: quiz with no questions handles pass rate correctly")
    void recomputeSnapshot_noQuestions_handlesGracefully() {
        // Given
        when(attemptRepository.getAttemptAggregateData(quizId)).thenReturn(List.of());
        when(attemptRepository.findCompletedWithAnswersByQuizId(quizId)).thenReturn(List.of());
        when(questionRepository.countByQuizId_Id(quizId)).thenReturn(0L); // No questions

        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.empty());
        when(snapshotRepository.save(any(QuizAnalyticsSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        QuizAnalyticsSnapshot result = service.recomputeSnapshot(quizId);

        // Then
        assertThat(result.getPassRate()).isZero();
        verify(snapshotRepository).save(any(QuizAnalyticsSnapshot.class));
    }

    @Test
    @DisplayName("recomputeSnapshot: mixed results (some passing, some failing)")
    void recomputeSnapshot_mixedResults_computesCorrectly() {
        // Given
        Object[] aggregateData = new Object[]{4L, 60.0, 90.0, 30.0};
        List<Object[]> aggregateList = new ArrayList<>();
        aggregateList.add(aggregateData);
        when(attemptRepository.getAttemptAggregateData(quizId)).thenReturn(aggregateList);
        when(questionRepository.countByQuizId_Id(quizId)).thenReturn(10L);

        // 2 passing, 2 failing
        Attempt pass1 = createAttempt(quizId, 8, 2); // 80%
        Attempt pass2 = createAttempt(quizId, 6, 4); // 60%
        Attempt fail1 = createAttempt(quizId, 4, 6); // 40%
        Attempt fail2 = createAttempt(quizId, 3, 7); // 30%

        when(attemptRepository.findCompletedWithAnswersByQuizId(quizId))
                .thenReturn(List.of(pass1, pass2, fail1, fail2));

        when(snapshotRepository.findByQuizId(quizId)).thenReturn(Optional.empty());
        when(snapshotRepository.save(any(QuizAnalyticsSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        QuizAnalyticsSnapshot result = service.recomputeSnapshot(quizId);

        // Then
        assertThat(result.getPassRate()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01)); // 2/4 = 50%
    }

    // ============ Helper Methods ============

    private Attempt createAttempt(UUID quizId, int correctAnswers, int incorrectAnswers) {
        Attempt attempt = new Attempt();
        attempt.setId(UUID.randomUUID());
        attempt.setStatus(AttemptStatus.COMPLETED);

        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        attempt.setQuiz(quiz);

        List<Answer> answers = new ArrayList<>();

        // Add correct answers
        for (int i = 0; i < correctAnswers; i++) {
            Answer answer = new Answer();
            answer.setIsCorrect(true);
            answers.add(answer);
        }

        // Add incorrect answers
        for (int i = 0; i < incorrectAnswers; i++) {
            Answer answer = new Answer();
            answer.setIsCorrect(false);
            answers.add(answer);
        }

        attempt.setAnswers(answers);
        return attempt;
    }
}

