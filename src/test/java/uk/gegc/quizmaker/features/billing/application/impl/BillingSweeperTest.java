package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.billing.domain.model.Reservation;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionSource;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for billing sweeper functionality that releases expired reservations.
 * Covers Day 6 requirements for sweeper backlog metrics and job state updates.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Billing Sweeper Tests")
@Execution(ExecutionMode.CONCURRENT)
class BillingSweeperTest {

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
    @DisplayName("Should release expired reservations and update job billing state")
    void shouldReleaseExpiredReservationsAndUpdateJobBillingState() {
        // Given
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UUID reservationId1 = UUID.randomUUID();
        UUID reservationId2 = UUID.randomUUID();
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now();

        Reservation expiredReservation1 = new Reservation();
        expiredReservation1.setId(reservationId1);
        expiredReservation1.setUserId(userId1);
        expiredReservation1.setState(ReservationState.ACTIVE);
        expiredReservation1.setEstimatedTokens(1000L);
        expiredReservation1.setExpiresAt(cutoff.minusMinutes(5));

        Reservation expiredReservation2 = new Reservation();
        expiredReservation2.setId(reservationId2);
        expiredReservation2.setUserId(userId2);
        expiredReservation2.setState(ReservationState.ACTIVE);
        expiredReservation2.setEstimatedTokens(500L);
        expiredReservation2.setExpiresAt(cutoff.minusMinutes(10));

        Balance balance1 = new Balance();
        balance1.setUserId(userId1);
        balance1.setAvailableTokens(2000L);
        balance1.setReservedTokens(1000L);

        Balance balance2 = new Balance();
        balance2.setUserId(userId2);
        balance2.setAvailableTokens(1500L);
        balance2.setReservedTokens(500L);

        QuizGenerationJob job1 = new QuizGenerationJob();
        job1.setId(jobId1);
        job1.setBillingReservationId(reservationId1);
        job1.setBillingState(BillingState.RESERVED);

        QuizGenerationJob job2 = new QuizGenerationJob();
        job2.setId(jobId2);
        job2.setBillingReservationId(reservationId2);
        job2.setBillingState(BillingState.RESERVED);

        List<Reservation> expiredReservations = Arrays.asList(expiredReservation1, expiredReservation2);

        when(reservationRepository.findByStateAndExpiresAtBefore(eq(ReservationState.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(expiredReservations);
        when(balanceRepository.findByUserId(userId1)).thenReturn(Optional.of(balance1));
        when(balanceRepository.findByUserId(userId2)).thenReturn(Optional.of(balance2));
        when(quizGenerationJobRepository.findByBillingReservationId(reservationId1))
                .thenReturn(Optional.of(job1));
        when(quizGenerationJobRepository.findByBillingReservationId(reservationId2))
                .thenReturn(Optional.of(job2));

        // When
        billingService.expireReservations();

        // Then
        // Verify reservations were processed
        verify(reservationRepository).findByStateAndExpiresAtBefore(eq(ReservationState.ACTIVE), any(LocalDateTime.class));

        // Verify balances updated for both users
        verify(balanceRepository).save(argThat(balance -> 
            balance.getUserId().equals(userId1) &&
            balance.getAvailableTokens() == 3000L &&
            balance.getReservedTokens() == 0L));
        verify(balanceRepository).save(argThat(balance -> 
            balance.getUserId().equals(userId2) &&
            balance.getAvailableTokens() == 2000L &&
            balance.getReservedTokens() == 0L));

        // Verify reservations marked as released
        verify(reservationRepository).save(argThat(res -> 
            res.getId().equals(reservationId1) && res.getState() == ReservationState.RELEASED));
        verify(reservationRepository).save(argThat(res -> 
            res.getId().equals(reservationId2) && res.getState() == ReservationState.RELEASED));

        // Verify job billing states updated
        verify(quizGenerationJobRepository).save(argThat(job -> 
            job.getId().equals(jobId1) && job.getBillingState() == BillingState.RELEASED));
        verify(quizGenerationJobRepository).save(argThat(job -> 
            job.getId().equals(jobId2) && job.getBillingState() == BillingState.RELEASED));

        // Verify transactions recorded
        verify(transactionRepository, times(2)).save(argThat(tx -> 
            tx.getType() == TokenTransactionType.RELEASE &&
            tx.getSource() == TokenTransactionSource.QUIZ_GENERATION));

        // Verify metrics recorded
        verify(metricsService).incrementTokensReleased(userId1, 1000L, "QUIZ_GENERATION");
        verify(metricsService).incrementTokensReleased(userId2, 500L, "QUIZ_GENERATION");
        verify(metricsService).incrementReservationReleased(userId1, 1000L);
        verify(metricsService).incrementReservationReleased(userId2, 500L);
        verify(metricsService).recordBalanceAvailable(userId1, 3000L);
        verify(metricsService).recordBalanceAvailable(userId2, 2000L);
        verify(metricsService).recordBalanceReserved(userId1, 0L);
        verify(metricsService).recordBalanceReserved(userId2, 0L);

        // Verify sweeper backlog metric recorded
        verify(metricsService).recordSweeperBacklog(2);
    }

    @Test
    @DisplayName("Should handle no expired reservations")
    void shouldHandleNoExpiredReservations() {
        // Given
        when(reservationRepository.findByStateAndExpiresAtBefore(eq(ReservationState.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(Arrays.asList());

        // When
        billingService.expireReservations();

        // Then
        verify(reservationRepository).findByStateAndExpiresAtBefore(eq(ReservationState.ACTIVE), any(LocalDateTime.class));
        verify(balanceRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        verify(metricsService, never()).incrementTokensReleased(any(), anyLong(), anyString());
        verify(metricsService).recordSweeperBacklog(0);
    }

    @Test
    @DisplayName("Should handle expired reservation when job not found")
    void shouldHandleExpiredReservationWhenJobNotFound() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now();

        Reservation expiredReservation = new Reservation();
        expiredReservation.setId(reservationId);
        expiredReservation.setUserId(userId);
        expiredReservation.setState(ReservationState.ACTIVE);
        expiredReservation.setEstimatedTokens(1000L);
        expiredReservation.setExpiresAt(cutoff.minusMinutes(5));

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(2000L);
        balance.setReservedTokens(1000L);

        when(reservationRepository.findByStateAndExpiresAtBefore(eq(ReservationState.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(expiredReservation));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(quizGenerationJobRepository.findByBillingReservationId(reservationId))
                .thenReturn(Optional.empty());

        // When
        billingService.expireReservations();

        // Then
        // Verify reservation and balance still updated even if job not found
        verify(balanceRepository).save(any());
        verify(reservationRepository).save(any());
        verify(transactionRepository).save(any());

        // Verify no job update attempted
        verify(quizGenerationJobRepository, never()).save(any());

        // Verify metrics still recorded
        verify(metricsService).incrementTokensReleased(userId, 1000L, "QUIZ_GENERATION");
        verify(metricsService).recordSweeperBacklog(1);
    }

    @Test
    @DisplayName("Should handle expired reservation when job billing state is not RESERVED")
    void shouldHandleExpiredReservationWhenJobBillingStateNotReserved() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now();

        Reservation expiredReservation = new Reservation();
        expiredReservation.setId(reservationId);
        expiredReservation.setUserId(userId);
        expiredReservation.setState(ReservationState.ACTIVE);
        expiredReservation.setEstimatedTokens(1000L);
        expiredReservation.setExpiresAt(cutoff.minusMinutes(5));

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(2000L);
        balance.setReservedTokens(1000L);

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.COMMITTED); // Already committed

        when(reservationRepository.findByStateAndExpiresAtBefore(eq(ReservationState.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(expiredReservation));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(quizGenerationJobRepository.findByBillingReservationId(reservationId))
                .thenReturn(Optional.of(job));

        // When
        billingService.expireReservations();

        // Then
        // Verify reservation and balance still updated
        verify(balanceRepository).save(any());
        verify(reservationRepository).save(any());
        verify(transactionRepository).save(any());

        // Verify job billing state not changed (already committed) - no save expected since state is COMMITTED
        verify(quizGenerationJobRepository, never()).save(any());

        // Verify metrics still recorded
        verify(metricsService).incrementTokensReleased(userId, 1000L, "QUIZ_GENERATION");
        verify(metricsService).recordSweeperBacklog(1);
    }

    @Test
    @DisplayName("Should continue processing other reservations when one fails")
    void shouldContinueProcessingOtherReservationsWhenOneFails() {
        // Given
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UUID reservationId1 = UUID.randomUUID();
        UUID reservationId2 = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now();

        Reservation expiredReservation1 = new Reservation();
        expiredReservation1.setId(reservationId1);
        expiredReservation1.setUserId(userId1);
        expiredReservation1.setState(ReservationState.ACTIVE);
        expiredReservation1.setEstimatedTokens(1000L);
        expiredReservation1.setExpiresAt(cutoff.minusMinutes(5));

        Reservation expiredReservation2 = new Reservation();
        expiredReservation2.setId(reservationId2);
        expiredReservation2.setUserId(userId2);
        expiredReservation2.setState(ReservationState.ACTIVE);
        expiredReservation2.setEstimatedTokens(500L);
        expiredReservation2.setExpiresAt(cutoff.minusMinutes(10));

        Balance balance1 = new Balance();
        balance1.setUserId(userId1);
        balance1.setAvailableTokens(2000L);
        balance1.setReservedTokens(1000L);

        Balance balance2 = new Balance();
        balance2.setUserId(userId2);
        balance2.setAvailableTokens(1500L);
        balance2.setReservedTokens(500L);

        List<Reservation> expiredReservations = Arrays.asList(expiredReservation1, expiredReservation2);

        when(reservationRepository.findByStateAndExpiresAtBefore(eq(ReservationState.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(expiredReservations);
        when(balanceRepository.findByUserId(userId1)).thenReturn(Optional.of(balance1));
        when(balanceRepository.findByUserId(userId2)).thenReturn(Optional.of(balance2));
        when(quizGenerationJobRepository.findByBillingReservationId(reservationId2))
                .thenReturn(Optional.empty());

        // Make the first reservation processing fail
        when(balanceRepository.save(any())).thenThrow(new RuntimeException("Database error"))
                .thenReturn(balance2);

        // When
        billingService.expireReservations();

        // Then
        // Verify both reservations were attempted to be processed
        verify(balanceRepository, times(2)).findByUserId(any());
        // First reservation fails, so only second gets saved
        verify(reservationRepository, times(1)).save(any());
        verify(transactionRepository, times(1)).save(any());

        // Verify metrics recorded for both (even though first failed)
        verify(metricsService).recordSweeperBacklog(2);
    }
}
