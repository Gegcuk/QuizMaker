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
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationStatus;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
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
 * Unit tests for quiz generation job cancellation delegation.
 * 
 * NOTE: After refactoring, QuizServiceImpl delegates to QuizGenerationFacade.
 * The actual implementation logic is tested in QuizGenerationFacadeImplComplexFlowsTest.
 * These tests verify the delegation works correctly.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
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
    @Mock
    private QuizQueryService quizQueryService;
    @Mock
    private QuizCommandService quizCommandService;
    @Mock
    private QuizRelationService quizRelationService;
    @Mock
    private QuizPublishingService quizPublishingService;
    @Mock
    private QuizVisibilityService quizVisibilityService;
    @Mock
    private QuizGenerationFacade quizGenerationFacade;

    private QuizServiceImpl quizService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Create QuizServiceImpl with new refactored dependencies
        quizService = new QuizServiceImpl(
                quizQueryService,
                quizCommandService,
                quizRelationService,
                quizPublishingService,
                quizVisibilityService,
                quizGenerationFacade
        );
        
        // Configure facade delegation
        lenient().when(quizGenerationFacade.cancelGenerationJob(any(UUID.class), anyString()))
                .thenAnswer(invocation -> {
                    // Return a mock status - delegation test
                    QuizGenerationJob job = new QuizGenerationJob();
                    job.setId((UUID) invocation.getArgument(0)); // Use the jobId from the call
                    job.setStatus(GenerationStatus.CANCELLED);
                    return QuizGenerationStatus.fromEntity(job, true);
                });
    }

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

        QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(job, true);
        when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

        // When
        quizService.cancelGenerationJob(jobId, username);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
    }

    @Test
    @DisplayName("cancelGenerationJob: when work started then commits used tokens")
    void cancelGenerationJob_whenWorkStarted_thenCommitsUsedTokens() {
        // Given
        UUID jobId = UUID.randomUUID();
        String username = "testuser";

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setStatus(GenerationStatus.CANCELLED);
        
        QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(job, true);
        when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

        // When
        quizService.cancelGenerationJob(jobId, username);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
    }
}

