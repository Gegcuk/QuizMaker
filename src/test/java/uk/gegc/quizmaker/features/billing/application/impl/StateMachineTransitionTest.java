package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.domain.exception.ReservationNotActiveException;
import uk.gegc.quizmaker.features.billing.domain.exception.CommitExceedsReservedException;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.mapping.BalanceMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.TokenTransactionMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.ReservationMapper;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.billing.testutils.BillingTestUtils;
import uk.gegc.quizmaker.features.billing.testutils.LedgerAsserts;
import uk.gegc.quizmaker.features.billing.testutils.BillingState;

import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Comprehensive tests for state machine transitions and illegal state skips.
 * 
 * Valid state transitions:
 * NONE → RESERVED (ACTIVE) → (COMMITTED | RELEASED | EXPIRED)
 * 
 * Illegal transitions:
 * - Skipping RESERVED state
 * - Operating on terminal states (COMMITTED, RELEASED, EXPIRED)
 * - Invalid state combinations
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("State Machine Transition Tests")
@Execution(ExecutionMode.CONCURRENT)
class StateMachineTransitionTest {

    @Mock
    private BillingProperties billingProperties;
    
    @Mock
    private BalanceRepository balanceRepository;
    
    @Mock
    private ReservationRepository reservationRepository;
    
    @Mock
    private TokenTransactionRepository transactionRepository;
    
    @Mock
    private QuizGenerationJobRepository quizGenerationJobRepository;
    
    @Mock
    private BalanceMapper balanceMapper;
    
    @Mock
    private TokenTransactionMapper transactionMapper;
    
    @Mock
    private ReservationMapper reservationMapper;
    
    @Mock
    private BillingMetricsService metricsService;
    
    @Mock
    private EntityManager entityManager;

    private BillingServiceImpl billingService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        billingService = new BillingServiceImpl(
            billingProperties,
            balanceRepository,
            transactionRepository,
            reservationRepository,
            quizGenerationJobRepository,
            balanceMapper,
            transactionMapper,
            reservationMapper,
            objectMapper,
            metricsService
        );
        
        // Inject dependencies to avoid NPE
        org.springframework.test.util.ReflectionTestUtils.setField(billingService, "objectMapper", objectMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(billingService, "entityManager", entityManager);
        
        // Setup common mocks
        lenient().when(billingProperties.getReservationTtlMinutes()).thenReturn(30);
    }

    @Nested
    @DisplayName("Valid State Transitions")
    class ValidStateTransitionTests {

        @Test
        @DisplayName("Should transition NONE → RESERVED (ACTIVE) successfully")
        void shouldTransitionNoneToReservedSuccessfully() {
            // Given
            UUID userId = UUID.randomUUID();
            long amount = 1000L;
            
            // Use utility to create mock balance
            Balance balance = BillingTestUtils.createMockBalance(userId, 5000L, 0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                res.setId(UUID.randomUUID());
                return res;
            });
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                return new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    res.getId(), userId, ReservationState.ACTIVE, res.getEstimatedTokens(), 0L, null, null, null, null);
            });

            // When
            var result = billingService.reserve(userId, amount, "test-ref", "test-key");

            // Then
            assertThat(result.state()).isEqualTo(ReservationState.ACTIVE);
            assertThat(result.estimatedTokens()).isEqualTo(amount);
            
            // I5: State machine validation - NONE → RESERVED transition
            LedgerAsserts.assertI5_StateMachine(BillingState.NONE, BillingState.RESERVED);
            
            // Verify reservation was saved with ACTIVE state
            verify(reservationRepository).save(argThat(reservation -> 
                reservation.getState() == ReservationState.ACTIVE &&
                reservation.getEstimatedTokens() == amount &&
                reservation.getCommittedTokens() == 0L
            ));
        }

        @Test
        @DisplayName("Should transition RESERVED → COMMITTED successfully")
        void shouldTransitionReservedToCommittedSuccessfully() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Use utility to create mock balance
            Balance balance = BillingTestUtils.createMockBalance(userId, 5000L, amount);
            
            // Use utility to create mock reservation
            Reservation reservation = BillingTestUtils.createMockReservation(userId, amount, ReservationState.ACTIVE);
            reservation.setId(reservationId);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());

            // When
            var result = billingService.commit(reservationId, amount, "test-ref", "test-key");

            // Then
            assertThat(result.committedTokens()).isEqualTo(amount);
            assertThat(result.releasedTokens()).isEqualTo(0L);
            
            // I5: State machine validation - RESERVED → COMMITTED transition
            LedgerAsserts.assertI5_StateMachine(BillingState.RESERVED, BillingState.COMMITTED);
            
            // Verify reservation state was updated to COMMITTED
            verify(reservationRepository).save(argThat(res -> 
                res.getState() == ReservationState.COMMITTED &&
                res.getCommittedTokens() == amount
            ));
        }

        @Test
        @DisplayName("Should transition RESERVED → RELEASED successfully")
        void shouldTransitionReservedToReleasedSuccessfully() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Use utility to create mock balance
            Balance balance = BillingTestUtils.createMockBalance(userId, 5000L, amount);
            
            // Use utility to create mock reservation
            Reservation reservation = BillingTestUtils.createMockReservation(userId, amount, ReservationState.ACTIVE);
            reservation.setId(reservationId);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());

            // When
            var result = billingService.release(reservationId, "test reason", "test-ref", "test-key");

            // Then
            assertThat(result.releasedTokens()).isEqualTo(amount);
            
            // I5: State machine validation - RESERVED → RELEASED transition
            LedgerAsserts.assertI5_StateMachine(BillingState.RESERVED, BillingState.RELEASED);
            
            // Verify reservation state was updated to RELEASED
            verify(reservationRepository).save(argThat(res -> 
                res.getState() == ReservationState.RELEASED
            ));
        }

        @Test
        @DisplayName("Should transition RESERVED → COMMITTED with partial release")
        void shouldTransitionReservedToCommittedWithPartialRelease() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long committedAmount = 600L;
            long expectedReleased = 400L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setCommittedTokens(0L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());

            // When
            var result = billingService.commit(reservationId, committedAmount, "test-ref", "test-key");

            // Then
            assertThat(result.committedTokens()).isEqualTo(committedAmount);
            assertThat(result.releasedTokens()).isEqualTo(expectedReleased);
            
            // Verify reservation state was updated to COMMITTED
            verify(reservationRepository).save(argThat(res -> 
                res.getState() == ReservationState.COMMITTED &&
                res.getCommittedTokens() == committedAmount
            ));
        }
    }

    @Nested
    @DisplayName("Illegal State Transitions")
    class IllegalStateTransitionTests {

        @Test
        @DisplayName("Should reject commit on non-ACTIVE reservation")
        void shouldRejectCommitOnNonActiveReservation() {
            // Given
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class)
              .hasMessageContaining("Reservation is not ACTIVE or not found");
        }

        @Test
        @DisplayName("Should reject release on non-ACTIVE reservation")
        void shouldRejectReleaseOnNonActiveReservation() {
            // Given
            UUID reservationId = UUID.randomUUID();
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.release(reservationId, "test reason", "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class)
              .hasMessageContaining("Reservation is not ACTIVE or not found");
        }

        @Test
        @DisplayName("Should reject commit on COMMITTED reservation")
        void shouldRejectCommitOnCommittedReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Create a COMMITTED reservation
            Reservation committedReservation = new Reservation();
            committedReservation.setId(reservationId);
            committedReservation.setUserId(userId);
            committedReservation.setEstimatedTokens(amount);
            committedReservation.setCommittedTokens(amount);
            committedReservation.setState(ReservationState.COMMITTED);
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class);
        }

        @Test
        @DisplayName("Should reject commit on RELEASED reservation")
        void shouldRejectCommitOnReleasedReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Create a RELEASED reservation
            Reservation releasedReservation = new Reservation();
            releasedReservation.setId(reservationId);
            releasedReservation.setUserId(userId);
            releasedReservation.setEstimatedTokens(amount);
            releasedReservation.setCommittedTokens(0L);
            releasedReservation.setState(ReservationState.RELEASED);
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class);
        }

        @Test
        @DisplayName("Should reject commit on EXPIRED reservation")
        void shouldRejectCommitOnExpiredReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Create an EXPIRED reservation
            Reservation expiredReservation = new Reservation();
            expiredReservation.setId(reservationId);
            expiredReservation.setUserId(userId);
            expiredReservation.setEstimatedTokens(amount);
            expiredReservation.setCommittedTokens(0L);
            expiredReservation.setState(ReservationState.EXPIRED);
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class);
        }

        @Test
        @DisplayName("Should reject release on COMMITTED reservation")
        void shouldRejectReleaseOnCommittedReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Create a COMMITTED reservation
            Reservation committedReservation = new Reservation();
            committedReservation.setId(reservationId);
            committedReservation.setUserId(userId);
            committedReservation.setEstimatedTokens(amount);
            committedReservation.setCommittedTokens(amount);
            committedReservation.setState(ReservationState.COMMITTED);
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.release(reservationId, "test reason", "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class);
        }

        @Test
        @DisplayName("Should reject release on RELEASED reservation")
        void shouldRejectReleaseOnReleasedReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Create a RELEASED reservation
            Reservation releasedReservation = new Reservation();
            releasedReservation.setId(reservationId);
            releasedReservation.setUserId(userId);
            releasedReservation.setEstimatedTokens(amount);
            releasedReservation.setCommittedTokens(0L);
            releasedReservation.setState(ReservationState.RELEASED);
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.release(reservationId, "test reason", "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class);
        }

        @Test
        @DisplayName("Should reject release on EXPIRED reservation")
        void shouldRejectReleaseOnExpiredReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Create an EXPIRED reservation
            Reservation expiredReservation = new Reservation();
            expiredReservation.setId(reservationId);
            expiredReservation.setUserId(userId);
            expiredReservation.setEstimatedTokens(amount);
            expiredReservation.setCommittedTokens(0L);
            expiredReservation.setState(ReservationState.EXPIRED);
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.release(reservationId, "test reason", "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class);
        }
    }

    @Nested
    @DisplayName("State Machine Edge Cases")
    class StateMachineEdgeCaseTests {

        @Test
        @DisplayName("Should handle commit exceeding reserved amount")
        void shouldHandleCommitExceedingReservedAmount() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long commitAmount = 1500L; // Exceeds reserved
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setCommittedTokens(0L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, commitAmount, "test-ref", "test-key");
            }).isInstanceOf(CommitExceedsReservedException.class)
              .hasMessageContaining("Actual usage exceeds reserved tokens");
        }

        @Test
        @DisplayName("Should handle zero amount commit")
        void shouldHandleZeroAmountCommit() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long commitAmount = 0L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setCommittedTokens(0L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, commitAmount, "test-ref", "test-key");
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("actualBillingTokens must be > 0");
        }

        @Test
        @DisplayName("Should handle negative amount commit")
        void shouldHandleNegativeAmountCommit() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long commitAmount = -100L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setCommittedTokens(0L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, commitAmount, "test-ref", "test-key");
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("actualBillingTokens must be > 0");
        }

        @Test
        @DisplayName("Should handle reservation with null state")
        void shouldHandleReservationWithNullState() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Create a reservation with null state (should not happen in practice)
            Reservation nullStateReservation = new Reservation();
            nullStateReservation.setId(reservationId);
            nullStateReservation.setUserId(userId);
            nullStateReservation.setEstimatedTokens(amount);
            nullStateReservation.setCommittedTokens(0L);
            nullStateReservation.setState(null);
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class);
        }

        @Test
        @DisplayName("Should handle reservation with CANCELLED state")
        void shouldHandleReservationWithCancelledState() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            // Create a CANCELLED reservation
            Reservation cancelledReservation = new Reservation();
            cancelledReservation.setId(reservationId);
            cancelledReservation.setUserId(userId);
            cancelledReservation.setEstimatedTokens(amount);
            cancelledReservation.setCommittedTokens(0L);
            cancelledReservation.setState(ReservationState.CANCELLED);
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "test-key");
            }).isInstanceOf(ReservationNotActiveException.class);
        }
    }

    @Nested
    @DisplayName("State Machine Consistency Tests")
    class StateMachineConsistencyTests {

        @Test
        @DisplayName("Should maintain state consistency across operations")
        void shouldMaintainStateConsistencyAcrossOperations() {
            // Given
            UUID userId = UUID.randomUUID();
            long amount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                res.setId(UUID.randomUUID());
                return res;
            });
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                return new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    res.getId(), userId, ReservationState.ACTIVE, res.getEstimatedTokens(), 0L, null, null, null, null);
            });

            // When - create reservation
            var reservation = billingService.reserve(userId, amount, "test-ref", "test-key");
            assertThat(reservation.state()).isEqualTo(ReservationState.ACTIVE);
            
            // When - commit the reservation
            Reservation commitReservation = new Reservation();
            commitReservation.setId(reservation.id());
            commitReservation.setUserId(userId);
            commitReservation.setEstimatedTokens(amount);
            commitReservation.setCommittedTokens(0L);
            commitReservation.setState(ReservationState.ACTIVE);
            commitReservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitReservation));
            
            var commitResult = billingService.commit(reservation.id(), amount, "test-ref", "commit-key");
            assertThat(commitResult.committedTokens()).isEqualTo(amount);
            
            // Then - verify state consistency
            verify(reservationRepository, atLeast(1)).save(argThat(res -> 
                res.getState() == ReservationState.ACTIVE || res.getState() == ReservationState.COMMITTED
            ));
        }

        @Test
        @DisplayName("Should prevent double commit on same reservation")
        void shouldPreventDoubleCommitOnSameReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(amount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(amount);
            reservation.setCommittedTokens(0L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation))
                .thenReturn(Optional.empty()); // Second call returns empty (already committed)
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());

            // When - first commit succeeds
            var result1 = billingService.commit(reservationId, amount, "test-ref", "commit-key-1");
            assertThat(result1.committedTokens()).isEqualTo(amount);
            
            // When - second commit should fail
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "commit-key-2");
            }).isInstanceOf(ReservationNotActiveException.class);
        }

        @Test
        @DisplayName("Should prevent double release on same reservation")
        void shouldPreventDoubleReleaseOnSameReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(amount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(amount);
            reservation.setCommittedTokens(0L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation))
                .thenReturn(Optional.empty()); // Second call returns empty (already released)
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());

            // When - first release succeeds
            var result1 = billingService.release(reservationId, "test reason", "test-ref", "release-key-1");
            assertThat(result1.releasedTokens()).isEqualTo(amount);
            
            // When - second release should fail
            assertThatThrownBy(() -> {
                billingService.release(reservationId, "test reason", "test-ref", "release-key-2");
            }).isInstanceOf(ReservationNotActiveException.class);
        }
    }
}
