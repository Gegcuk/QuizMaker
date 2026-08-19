package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageRecordResult;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationProviderUsageRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Provider usage transient persistence retries")
class ProviderUsageServiceRetryTest {

    @Mock private QuizGenerationJobRepository jobRepository;
    @Mock private QuizGenerationProviderUsageRepository usageRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private SimpleMeterRegistry meterRegistry;
    private ProviderUsageServiceImpl service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ProviderUsageServiceImpl(
                jobRepository,
                usageRepository,
                transactionManager,
                Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC),
                meterRegistry
        );
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    }

    @Test
    @DisplayName("transient database conflict retries in a new transaction and records once")
    void transientConflictRetriesAndRecordsOnce() {
        UUID jobId = UUID.randomUUID();
        UUID providerAttemptId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);

        when(jobRepository.findByIdForUpdate(jobId))
                .thenThrow(new TransientDataAccessResourceException("deadlock"))
                .thenReturn(Optional.of(job));
        when(usageRepository.findByJobIdAndProviderAttemptId(jobId, providerAttemptId))
                .thenReturn(Optional.empty());
        when(usageRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobRepository.saveAndFlush(job)).thenReturn(job);

        assertThat(service.recordStarted(jobId, providerAttemptId))
                .isEqualTo(ProviderUsageRecordResult.RECORDED);

        assertThat(job.getProviderLlmTokens()).isNull();
        assertThat(job.getProviderUsageState()).isEqualTo(ProviderUsageState.INCOMPLETE);
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager).commit(transactionStatus);
        verify(usageRepository).saveAndFlush(any());
        assertThat(counter("retry")).isEqualTo(1.0);
        assertThat(counter("started")).isEqualTo(1.0);
        assertThat(counter("failure")).isZero();
    }

    @Test
    @DisplayName("exhausted transient failures are surfaced after the bounded retry budget")
    void exhaustedTransientFailuresAreSurfaced() {
        UUID jobId = UUID.randomUUID();
        UUID providerAttemptId = UUID.randomUUID();
        when(jobRepository.findByIdForUpdate(jobId))
                .thenThrow(new TransientDataAccessResourceException("database unavailable"));

        assertThatThrownBy(() -> service.recordStarted(jobId, providerAttemptId))
                .isInstanceOf(TransientDataAccessResourceException.class)
                .hasMessage("database unavailable");

        verify(transactionManager, times(3)).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
        verify(usageRepository, never()).saveAndFlush(any());
        assertThat(counter("retry")).isEqualTo(2.0);
        assertThat(counter("failure")).isEqualTo(1.0);
        assertThat(counter("started")).isZero();
    }

    @Test
    @DisplayName("unavailable transaction storage is retried without pretending a transaction rolled back")
    void unavailableTransactionStorageIsRetried() {
        UUID jobId = UUID.randomUUID();
        UUID providerAttemptId = UUID.randomUUID();
        when(transactionManager.getTransaction(any()))
                .thenThrow(new CannotCreateTransactionException("cannot connect"));

        assertThatThrownBy(() -> service.recordStarted(jobId, providerAttemptId))
                .isInstanceOf(CannotCreateTransactionException.class)
                .hasMessage("cannot connect");

        verify(transactionManager, times(3)).getTransaction(any());
        verify(transactionManager, never()).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(jobRepository, never()).findByIdForUpdate(any());
        assertThat(counter("retry")).isEqualTo(2.0);
        assertThat(counter("failure")).isEqualTo(1.0);
        assertThat(counter("started")).isZero();
    }

    private double counter(String outcome) {
        return meterRegistry.get("quiz.generation.provider.usage")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
