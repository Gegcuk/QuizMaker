package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationCoverageSnapshot;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageException;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationCoverageOutcome;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationCoverage;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationFinalizationState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationCoverageRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quiz generation coverage service")
class QuizGenerationCoverageServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @Mock
    private QuizGenerationCoverageRepository coverageRepository;

    @Mock
    private QuizGenerationJobRepository jobRepository;

    private SimpleMeterRegistry meterRegistry;
    private QuizGenerationCoverageServiceImpl service;
    private QuizGenerationJob job;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new QuizGenerationCoverageServiceImpl(
                coverageRepository,
                jobRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry
        );
        job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
        job.setFinalizationState(QuizGenerationFinalizationState.NOT_STARTED);
    }

    @Test
    @DisplayName("Eligible reconciliation persists immutable aggregate and per-type facts")
    void eligibleDecisionIsPersisted() {
        GenerationCoverageSnapshot snapshot = partialSnapshot();
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(coverageRepository.findAllWithTypesByJobIdIn(List.of(job.getId()))).thenReturn(List.of());

        service.saveDecision(job.getId(), snapshot);

        ArgumentCaptor<QuizGenerationCoverage> captor =
                ArgumentCaptor.forClass(QuizGenerationCoverage.class);
        verify(coverageRepository).saveAndFlush(captor.capture());
        QuizGenerationCoverage saved = captor.getValue();
        assertThat(saved.getJobId()).isEqualTo(job.getId());
        assertThat(saved.getOutcome()).isEqualTo(GenerationCoverageOutcome.PARTIAL);
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(saved.getTypes()).extracting(type -> type.getQuestionType())
                .containsExactly(QuestionType.MCQ_SINGLE, QuestionType.FILL_GAP);
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "saved").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.decisions", "outcome", "partial").count()).isEqualTo(1.0);
        assertThat(meterRegistry.summary(
                "quiz.generation.coverage.count", "count", "requested").totalAmount()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("Identical retry is successful even after the job becomes terminal")
    void identicalRetryIsIdempotentAfterTerminalTransition() {
        GenerationCoverageSnapshot snapshot = partialSnapshot();
        job.setStatus(GenerationStatus.COMPLETED);
        QuizGenerationCoverage existing = entity(job.getId(), snapshot);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(coverageRepository.findAllWithTypesByJobIdIn(List.of(job.getId())))
                .thenReturn(List.of(existing));

        service.saveDecision(job.getId(), snapshot);

        verify(coverageRepository, never()).saveAndFlush(any());
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "duplicate").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Different retry is rejected and never replaces immutable facts")
    void conflictingRetryIsRejected() {
        QuizGenerationCoverage existing = entity(job.getId(), partialSnapshot());
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(coverageRepository.findAllWithTypesByJobIdIn(List.of(job.getId())))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.saveDecision(job.getId(), failedSnapshot()))
                .isInstanceOf(QuizGenerationCoverageException.class)
                .hasMessageContaining("different generation coverage");

        verify(coverageRepository, never()).saveAndFlush(any());
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "conflict").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Cancellation that wins before reconciliation rejects a new decision")
    void terminalJobRejectsFirstDecision() {
        job.setStatus(GenerationStatus.CANCELLED);
        job.setFinalizationState(QuizGenerationFinalizationState.CANCELLED);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(coverageRepository.findAllWithTypesByJobIdIn(List.of(job.getId()))).thenReturn(List.of());

        assertThatThrownBy(() -> service.saveDecision(job.getId(), partialSnapshot()))
                .isInstanceOf(QuizGenerationCoverageException.class)
                .hasMessageContaining("no longer eligible");

        verify(coverageRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Missing generation job rejects coverage without touching coverage storage")
    void missingJobIsRejected() {
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveDecision(job.getId(), partialSnapshot()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(job.getId().toString());

        verifyNoInteractions(coverageRepository);
    }

    @Test
    @DisplayName("Persistence failure is propagated and recorded without reporting a saved decision")
    void persistenceFailureIsObservable() {
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(coverageRepository.findAllWithTypesByJobIdIn(List.of(job.getId()))).thenReturn(List.of());
        doThrow(new DataAccessResourceFailureException("storage unavailable"))
                .when(coverageRepository).saveAndFlush(any());

        assertThatThrownBy(() -> service.saveDecision(job.getId(), partialSnapshot()))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("storage unavailable");

        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "persistence_failed").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "saved").count())
                .isZero();
    }

    @Test
    @DisplayName("Batch reads return deterministic immutable snapshots with one repository call")
    void batchReadMapsCoverageOnce() {
        UUID otherJobId = UUID.randomUUID();
        when(coverageRepository.findAllWithTypesByJobIdIn(any()))
                .thenReturn(List.of(entity(job.getId(), partialSnapshot()), entity(otherJobId, completeSnapshot())));

        Map<UUID, GenerationCoverageSnapshot> result =
                service.findByJobIds(List.of(job.getId(), otherJobId, job.getId()));

        assertThat(result).containsOnlyKeys(job.getId(), otherJobId);
        assertThat(result.get(job.getId()).types())
                .extracting(GenerationCoverageSnapshot.TypeCoverage::questionType)
                .containsExactly(QuestionType.MCQ_SINGLE, QuestionType.FILL_GAP);
        verify(coverageRepository).findAllWithTypesByJobIdIn(any());
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Malformed persisted facts are omitted and reported without breaking other job statuses")
    void malformedPersistedCoverageIsObservableAndOmitted() {
        QuizGenerationCoverage malformed = new QuizGenerationCoverage(
                job.getId(), GenerationCoverageOutcome.PARTIAL, 80,
                10, 9, 2, 0, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        malformed.addType(QuestionType.MCQ_SINGLE, 10, 9, 1);
        when(coverageRepository.findAllWithTypesByJobIdIn(any())).thenReturn(List.of(malformed));

        Map<UUID, GenerationCoverageSnapshot> result = service.findByJobIds(List.of(job.getId()));

        assertThat(result).isEmpty();
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "invalid_read").count()).isEqualTo(1.0);
    }

    private QuizGenerationCoverage entity(UUID jobId, GenerationCoverageSnapshot snapshot) {
        QuizGenerationCoverage entity = new QuizGenerationCoverage(
                jobId,
                snapshot.outcome(),
                snapshot.thresholdPercent(),
                snapshot.requestedTotal(),
                snapshot.acceptedTotal(),
                snapshot.missingTotal(),
                snapshot.discardedTotal(),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
        snapshot.types().forEach(type -> entity.addType(
                type.questionType(), type.requested(), type.accepted(), type.missing()));
        return entity;
    }

    private GenerationCoverageSnapshot partialSnapshot() {
        return new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.PARTIAL,
                80,
                10,
                9,
                1,
                2,
                List.of(
                        new GenerationCoverageSnapshot.TypeCoverage(QuestionType.FILL_GAP, 5, 4, 1),
                        new GenerationCoverageSnapshot.TypeCoverage(QuestionType.MCQ_SINGLE, 5, 5, 0)
                )
        );
    }

    private GenerationCoverageSnapshot failedSnapshot() {
        return new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.FAILED_THRESHOLD,
                80,
                10,
                8,
                2,
                0,
                List.of(new GenerationCoverageSnapshot.TypeCoverage(
                        QuestionType.MCQ_SINGLE, 10, 8, 2))
        );
    }

    private GenerationCoverageSnapshot completeSnapshot() {
        return new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.COMPLETE,
                80,
                5,
                5,
                0,
                0,
                List.of(new GenerationCoverageSnapshot.TypeCoverage(
                        QuestionType.OPEN, 5, 5, 0))
        );
    }
}
