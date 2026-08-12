package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.GeneratedQuizCheckpoint;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointException;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointService;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationFinalizationState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOutputCheckpoint;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationOutputCheckpointRepository;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quiz generation checkpoint service")
class QuizGenerationCheckpointServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Mock
    private QuizGenerationOutputCheckpointRepository checkpointRepository;

    @Mock
    private QuizGenerationJobRepository jobRepository;

    private QuizGenerationCheckpointCodec codec;
    private QuizJobProperties properties;
    private QuizGenerationCheckpointServiceImpl service;
    private QuizGenerationJob job;

    @BeforeEach
    void setUp() {
        codec = new QuizGenerationCheckpointCodec(new ObjectMapper().findAndRegisterModules());
        properties = new QuizJobProperties();
        service = new QuizGenerationCheckpointServiceImpl(
                checkpointRepository,
                jobRepository,
                codec,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry()
        );
        job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
    }

    @Test
    @DisplayName("Save locks the job and persists one immutable scalar checkpoint")
    void savePersistsCheckpointForEligibleJob() {
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(checkpointRepository.findById(job.getId())).thenReturn(Optional.empty());

        service.save(job.getId(), questions());

        ArgumentCaptor<QuizGenerationOutputCheckpoint> captor =
                ArgumentCaptor.forClass(QuizGenerationOutputCheckpoint.class);
        verify(checkpointRepository).saveAndFlush(captor.capture());
        QuizGenerationOutputCheckpoint saved = captor.getValue();
        assertThat(saved.getJobId()).isEqualTo(job.getId());
        assertThat(saved.getSchemaVersion()).isEqualTo((short) 1);
        assertThat(saved.getQuestionCount()).isEqualTo(1);
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(saved.getPayload()).contains("Durable question");
        assertThat(saved.getPayload()).doesNotContain("quizId", "tags", "createdAt");
    }

    @Test
    @DisplayName("Identical retry is idempotent and does not rewrite checkpoint")
    void identicalRetryDoesNotRewriteCheckpoint() {
        QuizGenerationCheckpointCodec.EncodedCheckpoint encoded = codec.encode(
                questions(), properties.getFinalization().getCheckpointMaxBytes());
        QuizGenerationOutputCheckpoint existing = new QuizGenerationOutputCheckpoint(
                job.getId(), encoded.schemaVersion(), encoded.payload(), encoded.questionCount(),
                LocalDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC));
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(checkpointRepository.findById(job.getId())).thenReturn(Optional.of(existing));

        service.save(job.getId(), questions());

        verify(checkpointRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Identical completion retry remains idempotent after finalization has claimed the checkpoint")
    void identicalRetryAfterFinalizationClaimDoesNotRewriteCheckpoint() {
        QuizGenerationCheckpointCodec.EncodedCheckpoint encoded = codec.encode(
                questions(), properties.getFinalization().getCheckpointMaxBytes());
        QuizGenerationOutputCheckpoint existing = new QuizGenerationOutputCheckpoint(
                job.getId(), encoded.schemaVersion(), encoded.payload(), encoded.questionCount(),
                LocalDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC));
        job.beginFinalization(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(checkpointRepository.findById(job.getId())).thenReturn(Optional.of(existing));

        service.save(job.getId(), questions());

        verify(checkpointRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Different retry for one job is rejected instead of replacing authoritative output")
    void conflictingRetryIsRejected() {
        QuizGenerationCheckpointCodec.EncodedCheckpoint encoded = codec.encode(
                questions(), properties.getFinalization().getCheckpointMaxBytes());
        QuizGenerationOutputCheckpoint existing = new QuizGenerationOutputCheckpoint(
                job.getId(), encoded.schemaVersion(), encoded.payload(), encoded.questionCount(),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(checkpointRepository.findById(job.getId())).thenReturn(Optional.of(existing));
        Question different = question();
        different.setQuestionText("Different output");

        assertThatThrownBy(() -> service.save(job.getId(), Map.of(0, List.of(different))))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("different generated output");

        verify(checkpointRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Terminal cancellation wins and rejects a late worker checkpoint")
    void terminalJobRejectsLateCheckpoint() {
        job.setStatus(GenerationStatus.CANCELLED);
        job.setFinalizationState(QuizGenerationFinalizationState.CANCELLED);
        when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.save(job.getId(), questions()))
                .isInstanceOf(QuizGenerationCheckpointException.class)
                .hasMessageContaining("no longer eligible");

        verify(checkpointRepository, never()).findById(any());
        verify(checkpointRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Load decodes the persisted snapshot without restoring JPA relationships")
    void loadDecodesPersistedSnapshot() {
        QuizGenerationCheckpointCodec.EncodedCheckpoint encoded = codec.encode(
                questions(), properties.getFinalization().getCheckpointMaxBytes());
        when(checkpointRepository.findById(job.getId())).thenReturn(Optional.of(
                new QuizGenerationOutputCheckpoint(
                        job.getId(), encoded.schemaVersion(), encoded.payload(), encoded.questionCount(),
                        LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))));

        GeneratedQuizCheckpoint checkpoint = service.getRequired(job.getId());

        assertThat(checkpoint.questionCount()).isEqualTo(1);
        assertThat(checkpoint.chunkQuestions().get(0).get(0).getQuestionText()).isEqualTo("Durable question");
        assertThat(checkpoint.chunkQuestions().get(0).get(0).getId()).isNull();
    }

    @Test
    @DisplayName("Checkpoint cleanup failure is observable and still propagates to roll back its caller")
    void deleteFailureIsCountedAndPropagated() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new QuizGenerationCheckpointServiceImpl(
                checkpointRepository,
                jobRepository,
                codec,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry
        );
        doThrow(new IllegalStateException("database unavailable"))
                .when(checkpointRepository).deleteByJobId(job.getId());

        assertThatThrownBy(() -> service.delete(job.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        assertThat(meterRegistry.counter(
                "quiz.generation.checkpoint.operations", "outcome", "delete_failed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "quiz.generation.checkpoint.operations", "outcome", "deleted").count()).isZero();
    }

    @Test
    @DisplayName("Recovery scan returns four bounded candidate classes with injected-clock cutoffs")
    void recoveryScanUsesBoundedQueriesAndInjectedClock() {
        UUID ready = UUID.randomUUID();
        UUID stalled = UUID.randomUUID();
        UUID orphaned = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        when(checkpointRepository.findCheckpointedJobIds(any(), any(), any(), any()))
                .thenReturn(List.of(ready));
        when(checkpointRepository.findStaleCheckpointedFinalizationJobIds(any(), any(), any(), any()))
                .thenReturn(List.of(stalled));
        when(checkpointRepository.findStaleUncheckpointedFinalizationJobIds(any(), any(), any(), any()))
                .thenReturn(List.of(orphaned));
        when(checkpointRepository.findExpiredUncheckpointedJobIds(any(), any(), any(), any(), any()))
                .thenReturn(List.of(expired));

        QuizGenerationCheckpointService.RecoveryBatch batch = service.findRecoveryBatch(300, 25);

        assertThat(batch.checkpointedNotStarted()).containsExactly(ready);
        assertThat(batch.checkpointedFinalizing()).containsExactly(stalled);
        assertThat(batch.uncheckpointedFinalizing()).containsExactly(orphaned);
        assertThat(batch.expiredUncheckpointed()).containsExactly(expired);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(checkpointRepository).findCheckpointedJobIds(
                eq(GenerationStatus.PROCESSING),
                eq(QuizGenerationFinalizationState.NOT_STARTED),
                cutoff.capture(),
                page.capture()
        );
        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.ofInstant(NOW.minusSeconds(300), ZoneOffset.UTC));
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
        verify(checkpointRepository).findExpiredUncheckpointedJobIds(
                eq(GenerationStatus.PROCESSING),
                eq(QuizGenerationFinalizationState.NOT_STARTED),
                eq(BillingState.RESERVED),
                eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                any(Pageable.class)
        );
    }

    private Map<Integer, List<Question>> questions() {
        return Map.of(0, List.of(question()));
    }

    private Question question() {
        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText("Durable question");
        question.setContent("{\"correctOptionId\":\"answer-1\"}");
        return question;
    }
}
