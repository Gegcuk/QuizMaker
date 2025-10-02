package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for quiz generation job cancellation with billing logic
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizService Cancellation Tests")
class QuizServiceCancellationTest {

    @Mock
    private QuizGenerationJobRepository jobRepository;

    @Mock
    private QuizGenerationJobService jobService;

    @Mock
    private BillingService billingService;

    @Mock
    private InternalBillingService internalBillingService;

    @Mock
    private QuizJobProperties quizJobProperties;

    @Mock
    private uk.gegc.quizmaker.shared.config.FeatureFlags featureFlags;

    @InjectMocks
    private QuizServiceImpl quizService;

    @Test
    @DisplayName("cancelGenerationJob: when no work started then only releases reservation")
    void cancelGenerationJob_whenNoWorkStarted_thenOnlyReleasesReservation() {
        // Given
        UUID jobId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String username = "testuser";

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setStatus(GenerationStatus.PROCESSING);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);
        job.setHasStartedAiCalls(false); // No work started
        job.setBillingEstimatedTokens(1000L);

        QuizJobProperties.Cancellation cancellation = new QuizJobProperties.Cancellation();
        cancellation.setCommitOnCancel(true);
        cancellation.setMinStartFeeTokens(0L);

        when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(job);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(featureFlags.isBilling()).thenReturn(true);
        when(quizJobProperties.getCancellation()).thenReturn(cancellation);
        when(billingService.release(eq(reservationId), anyString(), anyString(), anyString()))
                .thenReturn(new ReleaseResultDto(reservationId, 1000L));

        // When
        quizService.cancelGenerationJob(jobId, username);

        // Then - Verify that release was called but commit was not
        verify(billingService, times(1)).release(eq(reservationId), anyString(), anyString(), anyString());
        verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("cancelGenerationJob: when work started then commits used tokens")
    void cancelGenerationJob_whenWorkStarted_thenCommitsUsedTokens() {
        // Given
        UUID jobId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String username = "testuser";

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setStatus(GenerationStatus.PROCESSING);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);
        job.setHasStartedAiCalls(true); // Work has started
        job.setActualTokens(500L); // Used 500 tokens
        job.setBillingEstimatedTokens(1000L);

        QuizJobProperties.Cancellation cancellation = new QuizJobProperties.Cancellation();
        cancellation.setCommitOnCancel(true);
        cancellation.setMinStartFeeTokens(0L);

        when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(job);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(featureFlags.isBilling()).thenReturn(true);
        when(quizJobProperties.getCancellation()).thenReturn(cancellation);
        when(internalBillingService.commit(eq(reservationId), eq(500L), anyString(), anyString()))
                .thenReturn(new CommitResultDto(reservationId, 500L, 500L)); // Committed 500, released 500

        // When
        quizService.cancelGenerationJob(jobId, username);

        // Then - Verify that commit was called with correct token amount
        verify(internalBillingService, times(1)).commit(eq(reservationId), eq(500L), anyString(), anyString());
        verify(billingService, never()).release(any(), anyString(), anyString(), anyString());
    }
}

