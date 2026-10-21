package uk.gegc.quizmaker.features.quiz.application.query.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationWorkRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenerationQueuePositionServiceImplTest {
    @Mock QuizGenerationWorkRepository repository;
    @Mock PlatformTransactionManager transactions;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
    final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    final MeterRegistry metrics = new SimpleMeterRegistry();
    GenerationQueuePositionServiceImpl service;

    @BeforeEach void setUp() {
        service = new GenerationQueuePositionServiceImpl(repository, clock, clock, metrics, transactions);
        lenient().when(transactions.getTransaction(any())).thenReturn(transaction);
    }

    @Test void readsOneSnapshotAndDerivesJobsAheadWithoutMutatingWork() {
        UUID id = UUID.randomUUID();
        when(repository.findQueuePositions(any(), eq("owner"), any(), any(), any())).thenReturn(List.of(row(id, 2L)));
        var result = service.findPositions(List.of(id, id), "owner").get(id);
        assertThat(result.queuePosition()).isEqualTo(2);
        assertThat(result.jobsAhead()).isEqualTo(1);
        assertThat(result.asOf()).isEqualTo(clock.instant());
        verify(repository).findQueuePositions(eq(java.util.Set.of(id)), eq("owner"), any(), any(), any());
        verifyNoMoreInteractions(repository);
        verify(transactions).getTransaction(argThat(def -> def.isReadOnly()
                && def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactions).commit(transaction);
    }

    @Test void absentIdentityAndEmptyPageNeverReadQueue() {
        assertThat(service.findPositions(List.of(UUID.randomUUID()), null)).isEmpty();
        assertThat(service.findPositions(List.of(UUID.randomUUID()), " ")).isEmpty();
        assertThat(service.findPositions(List.of(), "owner")).isEmpty();
        verifyNoInteractions(repository, transactions);
    }

    @Test void unavailablePositionIsNotFabricated() {
        UUID id = UUID.randomUUID();
        when(repository.findQueuePositions(any(), anyString(), any(), any(), any())).thenReturn(List.of(row(id, null)));
        assertThat(service.findPositions(List.of(id), "owner")).isEmpty();
        assertThat(metrics.counter("quiz.generation.queue.position.reads", "outcome", "unavailable").count()).isEqualTo(1);
    }

    @Test void timeoutRollsBackOnlyOptionalReadAndReturnsNoPositions() {
        when(repository.findQueuePositions(any(), anyString(), any(), any(), any())).thenThrow(new QueryTimeoutException("synthetic"));
        assertThat(service.findPositions(List.of(UUID.randomUUID()), "owner")).isEmpty();
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        assertThat(metrics.counter("quiz.generation.queue.position.reads", "outcome", "query_failed").count()).isEqualTo(1);
    }

    @Test void transactionCompletionFailureAlsoLeavesPositionUnavailable() {
        UUID id = UUID.randomUUID();
        when(repository.findQueuePositions(any(), anyString(), any(), any(), any())).thenReturn(List.of(row(id, 1L)));
        doThrow(new TransactionSystemException("synthetic")).when(transactions).commit(any());
        assertThat(service.findPositions(List.of(id), "owner")).isEmpty();
    }

    @Test void metricsFailureCannotChangeSuccessfulResponse() {
        MeterRegistry broken = mock(MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("synthetic"));
        when(broken.timer(anyString(), any(String[].class))).thenThrow(new IllegalStateException("synthetic"));
        service = new GenerationQueuePositionServiceImpl(repository, clock, clock, broken, transactions);
        UUID id = UUID.randomUUID();
        when(repository.findQueuePositions(any(), anyString(), any(), any(), any())).thenReturn(List.of(row(id, 3L)));
        assertThat(service.findPositions(List.of(id), "owner").get(id).jobsAhead()).isEqualTo(2);
    }

    private QuizGenerationWorkRepository.QueuePositionRow row(UUID id, Long position) {
        return new QuizGenerationWorkRepository.QueuePositionRow() {
            public String getJobId() { return id.toString(); }
            public Long getQueuePosition() { return position; }
            public Integer getInconsistent() { return 0; }
        };
    }
}
