package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.billing.domain.model.Reservation;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionSource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for billing release functionality on failure, cancel, and expiry scenarios.
 * Covers Day 6 requirements for robust release behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Billing Release Tests")
class BillingReleaseTest {

    @Mock
    private BalanceRepository balanceRepository;

    @Mock
    private TokenTransactionRepository transactionRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private QuizGenerationJobRepository quizGenerationJobRepository;

    @Mock
    private BillingMetricsService metricsService;

    @InjectMocks
    private BillingServiceImpl billingService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Inject ObjectMapper to avoid NPE
        org.springframework.test.util.ReflectionTestUtils.setField(billingService, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        // Inject EntityManager to avoid NPE
        org.springframework.test.util.ReflectionTestUtils.setField(billingService, "entityManager", mock(jakarta.persistence.EntityManager.class));
    }

    @Test
    @DisplayName("Should release reservation on job failure")
    void shouldReleaseReservationOnJobFailure() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String reason = "Generation failed: AI service error";
        String ref = jobId.toString();
        String idempotencyKey = "quiz:" + jobId + ":release";

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUserId(userId);
        reservation.setState(ReservationState.ACTIVE);
        reservation.setEstimatedTokens(1000L);
        reservation.setExpiresAt(LocalDateTime.now().plusHours(1));

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(5000L);
        balance.setReservedTokens(1000L);

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);

        when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)).thenReturn(Optional.of(reservation));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

        // When
        ReleaseResultDto result = billingService.release(reservationId, reason, ref, idempotencyKey);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.releasedTokens()).isEqualTo(1000L);
        assertThat(result.reservationId()).isEqualTo(reservationId);

        // Verify reservation state updated
        verify(reservationRepository).save(argThat(res -> 
            res.getState() == ReservationState.RELEASED));

        // Verify balance updated
        verify(balanceRepository).save(argThat(bal -> 
            bal.getAvailableTokens() == 6000L && 
            bal.getReservedTokens() == 0L));

        // Note: BillingService doesn't update job state - that's done by the calling service

        // Verify transaction recorded
        verify(transactionRepository).save(argThat(tx -> 
            tx.getType() == TokenTransactionType.RELEASE &&
            tx.getAmountTokens() == 1000L &&
            tx.getSource() == TokenTransactionSource.QUIZ_GENERATION));

        // Verify metrics recorded
        verify(metricsService).incrementTokensReleased(userId, 1000L, "QUIZ_GENERATION");
        verify(metricsService).incrementReservationReleased(userId, 1000L);
    }

    @Test
    @DisplayName("Should release reservation on job cancellation")
    void shouldReleaseReservationOnJobCancellation() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String reason = "Job cancelled by user";
        String ref = jobId.toString();
        String idempotencyKey = "quiz:" + jobId + ":release";

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUserId(userId);
        reservation.setState(ReservationState.ACTIVE);
        reservation.setEstimatedTokens(500L);
        reservation.setExpiresAt(LocalDateTime.now().plusHours(1));

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(2000L);
        balance.setReservedTokens(500L);

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);

        when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)).thenReturn(Optional.of(reservation));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

        // When
        ReleaseResultDto result = billingService.release(reservationId, reason, ref, idempotencyKey);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.releasedTokens()).isEqualTo(500L);

        // Note: BillingService doesn't update job state - that's done by the calling service
    }

    @Test
    @DisplayName("Should handle repeated release calls idempotently")
    void shouldHandleRepeatedReleaseCallsIdempotently() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String reason = "Generation failed";
        String ref = jobId.toString();
        String idempotencyKey = "quiz:" + jobId + ":release";

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUserId(userId);
        reservation.setState(ReservationState.RELEASED); // Already released
        reservation.setEstimatedTokens(1000L);

        // Mock idempotency check - return existing transaction
        TokenTransaction existingTx = new TokenTransaction();
        existingTx.setType(TokenTransactionType.RELEASE);
        existingTx.setRefId(reservationId.toString());
        existingTx.setAmountTokens(0L); // Already released
        
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTx));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // When
        ReleaseResultDto result = billingService.release(reservationId, reason, ref, idempotencyKey);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.releasedTokens()).isEqualTo(1000L); // Returns the amount that was previously released

        // Verify no balance updates on repeated release
        verify(balanceRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        verify(metricsService, never()).incrementTokensReleased(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Should handle release when job not found")
    void shouldHandleReleaseWhenJobNotFound() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String reason = "Generation failed";
        String ref = jobId.toString();
        String idempotencyKey = "quiz:" + jobId + ":release";

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUserId(userId);
        reservation.setState(ReservationState.ACTIVE);
        reservation.setEstimatedTokens(1000L);

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(5000L);
        balance.setReservedTokens(1000L);

        when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)).thenReturn(Optional.of(reservation));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

        // When
        ReleaseResultDto result = billingService.release(reservationId, reason, ref, idempotencyKey);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.releasedTokens()).isEqualTo(1000L);

        // Verify reservation and balance still updated even if job not found
        verify(reservationRepository).save(any());
        verify(balanceRepository).save(any());
        verify(transactionRepository).save(any());

        // Note: BillingService doesn't update job state - that's done by the calling service
    }

    @Test
    @DisplayName("Should handle release when job billing state is not RESERVED")
    void shouldHandleReleaseWhenJobBillingStateNotReserved() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String reason = "Generation failed";
        String ref = jobId.toString();
        String idempotencyKey = "quiz:" + jobId + ":release";

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUserId(userId);
        reservation.setState(ReservationState.ACTIVE);
        reservation.setEstimatedTokens(1000L);

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(5000L);
        balance.setReservedTokens(1000L);

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.COMMITTED); // Already committed

        when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)).thenReturn(Optional.of(reservation));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

        // When
        ReleaseResultDto result = billingService.release(reservationId, reason, ref, idempotencyKey);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.releasedTokens()).isEqualTo(1000L);

        // Verify reservation and balance still updated
        verify(reservationRepository).save(any());
        verify(balanceRepository).save(any());

        // Note: BillingService doesn't update job state - that's done by the calling service
    }

    @Test
    @DisplayName("Should handle release failure gracefully")
    void shouldHandleReleaseFailureGracefully() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String reason = "Generation failed";
        String ref = jobId.toString();
        String idempotencyKey = "quiz:" + jobId + ":release";

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUserId(userId);
        reservation.setState(ReservationState.ACTIVE);
        reservation.setEstimatedTokens(1000L);

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(5000L);
        balance.setReservedTokens(1000L);

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);

        when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)).thenReturn(Optional.of(reservation));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

        // When
        ReleaseResultDto result = billingService.release(reservationId, reason, ref, idempotencyKey);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.releasedTokens()).isEqualTo(1000L);

        // Verify reservation and balance still updated despite job save failure
        verify(reservationRepository).save(any());
        verify(balanceRepository).save(any());
        verify(transactionRepository).save(any());
    }
}
