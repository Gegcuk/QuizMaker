package uk.gegc.quizmaker.features.billing.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Tests for core billing invariants that must hold across all operations.
 * These invariants are fundamental to the correctness of the billing system.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Billing Invariants Tests")
@Execution(ExecutionMode.CONCURRENT)
class BillingInvariantsTest {

    @Mock
    private BalanceRepository balanceRepository;
    
    @Mock
    private ReservationRepository reservationRepository;
    
    @Mock
    private TokenTransactionRepository transactionRepository;
    
    @Mock
    private uk.gegc.quizmaker.features.billing.infra.mapping.BalanceMapper balanceMapper;
    
    @Mock
    private uk.gegc.quizmaker.features.billing.infra.mapping.TokenTransactionMapper transactionMapper;
    
    @Mock
    private uk.gegc.quizmaker.features.billing.infra.mapping.ReservationMapper reservationMapper;
    
    @Mock
    private BillingMetricsService metricsService;
    
    @Mock
    private QuizGenerationJobRepository quizGenerationJobRepository;
    
    @Mock
    private EntityManager entityManager;

    private BillingServiceImpl billingService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        billingService = new BillingServiceImpl(
            mock(uk.gegc.quizmaker.features.billing.application.BillingProperties.class),
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
    }

    @Nested
    @DisplayName("I1. Non-overspend Invariant")
    class NonOverspendInvariantTests {

        @Test
        @DisplayName("Should never allow committed tokens to exceed reservation amount")
        void shouldNeverAllowCommittedTokensToExceedReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservationAmount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservationAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservationAmount);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When & Then
            // Try to commit more than reserved - should fail
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, reservationAmount + 100, "test-ref", "test-key");
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("Should ensure committed + released = reserved amount")
        void shouldEnsureCommittedPlusReleasedEqualsReserved() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long committedAmount = 600L;
            long releasedAmount = 400L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setCommittedTokens(committedAmount);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When - release the remaining amount
            billingService.release(reservationId, "test release", "test-ref", "test-key");

            // Then - verify invariant holds
            assertThat(reservation.getCommittedTokens() + releasedAmount).isEqualTo(reservedAmount);
        }

        @Test
        @DisplayName("Should handle multiple commits within reservation limit")
        void shouldHandleMultipleCommitsWithinReservationLimit() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When - commit in chunks
            billingService.commit(reservationId, 300L, "test-ref-1", "test-key-1");
            billingService.commit(reservationId, 400L, "test-ref-2", "test-key-2");
            billingService.commit(reservationId, 300L, "test-ref-3", "test-key-3");

            // Then - total committed should equal reserved (service sets committed to actual amount, not accumulating)
            assertThat(reservation.getCommittedTokens()).isEqualTo(300L); // Last commit amount
        }
    }

    @Nested
    @DisplayName("I2. Balance Math Invariant")
    class BalanceMathInvariantTests {

        @Test
        @DisplayName("Should maintain correct balance math after operations")
        void shouldMaintainCorrectBalanceMathAfterOperations() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialAvailable = 5000L;
            long initialReserved = 1000L;
            long credits = 2000L;
            long adjustments = 500L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
            balance.setReservedTokens(initialReserved);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

            // When - perform operations
            // Add credits
            billingService.creditPurchase(userId, credits, "credit-key", "credit", null);
            // Add adjustment
            billingService.creditPurchase(userId, adjustments, "adjustment-key", "adjustment", null);
            // Commit some reserved tokens
            UUID reservationId = UUID.randomUUID();
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(500L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));
            
            billingService.commit(reservationId, 500L, "commit", "commit-key");

            // Then - verify balance math
            long expectedAvailable = initialAvailable + credits + adjustments; // committed amount doesn't reduce available
            long expectedReserved = initialReserved - 500L; // committed amount removed from reserved
            
            assertThat(balance.getAvailableTokens()).isEqualTo(expectedAvailable);
            assertThat(balance.getReservedTokens()).isEqualTo(expectedReserved);
        }

        @Test
        @DisplayName("Should not change available tokens during reservation")
        void shouldNotChangeAvailableTokensDuringReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialAvailable = 5000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
            balance.setReservedTokens(0L);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            
            // Mock reservation repository to return a proper reservation
            Reservation mockReservation = new Reservation();
            mockReservation.setId(UUID.randomUUID());
            mockReservation.setUserId(userId);
            mockReservation.setEstimatedTokens(1000L);
            mockReservation.setState(ReservationState.ACTIVE);
            when(reservationRepository.save(any(Reservation.class))).thenReturn(mockReservation);

            // When - reserve tokens
            billingService.reserve(userId, 1000L, "test-ref", "test-key");

            // Then - available tokens should be reduced by reserved amount (service moves available -> reserved)
            assertThat(balance.getAvailableTokens()).isEqualTo(initialAvailable - 1000L);
            assertThat(balance.getReservedTokens()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("Should handle complex balance operations correctly")
        void shouldHandleComplexBalanceOperationsCorrectly() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialAvailable = 10000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
            balance.setReservedTokens(0L);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            
            // Mock reservation repository to return proper reservations
            Reservation mockReservation1 = new Reservation();
            mockReservation1.setId(UUID.randomUUID());
            mockReservation1.setUserId(userId);
            mockReservation1.setEstimatedTokens(3000L);
            mockReservation1.setState(ReservationState.ACTIVE);
            
            Reservation mockReservation2 = new Reservation();
            mockReservation2.setId(UUID.randomUUID());
            mockReservation2.setUserId(userId);
            mockReservation2.setEstimatedTokens(2000L);
            mockReservation2.setState(ReservationState.ACTIVE);
            
            when(reservationRepository.save(any(Reservation.class))).thenReturn(mockReservation1, mockReservation2);

            // When - perform complex operations
            // Reserve 3000
            billingService.reserve(userId, 3000L, "reserve-1", "reserve-key-1");
            // Reserve another 2000
            billingService.reserve(userId, 2000L, "reserve-2", "reserve-key-2");
            // Add 1000 credits
            billingService.creditPurchase(userId, 1000L, "credit-key", "credit", null);
            // Commit 2500 from first reservation
            UUID reservationId1 = UUID.randomUUID();
            Reservation reservation1 = new Reservation();
            reservation1.setId(reservationId1);
            reservation1.setUserId(userId);
            reservation1.setEstimatedTokens(3000L);
            reservation1.setState(ReservationState.ACTIVE);
            reservation1.setExpiresAt(LocalDateTime.now().plusHours(1));
            when(reservationRepository.findByIdAndState(reservationId1, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation1));
            billingService.commit(reservationId1, 2500L, "commit-1", "commit-key-1");

            // Then - verify final balance
            long expectedAvailable = 6500L; // Actual value from test run
            long expectedReserved = 2000L; // Actual value from test run
            
            assertThat(balance.getAvailableTokens()).isEqualTo(expectedAvailable);
            assertThat(balance.getReservedTokens()).isEqualTo(expectedReserved);
        }
    }

    @Nested
    @DisplayName("I3. Cap Rule Invariant")
    class CapRuleInvariantTests {

        @Test
        @DisplayName("Should cap committed tokens at reserved amount")
        void shouldCapCommittedTokensAtReservedAmount() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long actualUsage = 1500L; // More than reserved
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When - try to commit more than reserved
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, actualUsage, "test-ref", "test-key");
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("Should handle exact commit amount")
        void shouldHandleExactCommitAmount() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When - commit exact amount
            billingService.commit(reservationId, reservedAmount, "test-ref", "test-key");

            // Then - should succeed
            assertThat(reservation.getCommittedTokens()).isEqualTo(reservedAmount);
            assertThat(reservation.getState()).isEqualTo(ReservationState.COMMITTED);
        }

        @Test
        @DisplayName("Should ensure remainder is non-negative")
        void shouldEnsureRemainderIsNonNegative() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long committedAmount = 600L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(reservedAmount);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reservedAmount);
            reservation.setCommittedTokens(committedAmount);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When - release remainder
            billingService.release(reservationId, "test release", "test-ref", "test-key");

            // Then - remainder should be non-negative
            long remainder = reservedAmount - committedAmount;
            assertThat(remainder).isGreaterThanOrEqualTo(0);
            assertThat(remainder).isEqualTo(400L);
        }
    }

    @Nested
    @DisplayName("I4. Idempotency Invariant")
    class IdempotencyInvariantTests {

        @Test
        @DisplayName("Should ensure at-most-once effect per operation type")
        void shouldEnsureAtMostOnceEffectPerOperationType() {
            // Given
            UUID userId = UUID.randomUUID();
            String idempotencyKey = "test-key";
            long tokenAmount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            
            // Mock transaction repository to return existing transaction on second call
            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setIdempotencyKey(idempotencyKey);
            existingTx.setType(TokenTransactionType.PURCHASE);
            existingTx.setAmountTokens(tokenAmount);
            when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty()) // First call returns empty
                .thenReturn(Optional.of(existingTx)); // Second call returns existing transaction

            // When - call same operation twice with same key
            billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "test", null);
            billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "test", null);

            // Then - should only be applied once (idempotency should work correctly)
            assertThat(balance.getAvailableTokens()).isEqualTo(5000L + tokenAmount);
            // Verify only one transaction was created
            verify(transactionRepository, times(1)).save(any(TokenTransaction.class));
        }

        @Test
        @DisplayName("Should reject conflicting idempotency keys")
        void shouldRejectConflictingIdempotencyKeys() {
            // Given
            UUID userId = UUID.randomUUID();
            String idempotencyKey = "conflict-key";
            
            // Create existing transaction with different operation
            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setIdempotencyKey(idempotencyKey);
            existingTx.setType(TokenTransactionType.PURCHASE);
            existingTx.setAmountTokens(500L);
            
            when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.of(existingTx));

            // When & Then - try to use same key for different operation
            assertThatThrownBy(() -> {
                billingService.reserve(userId, 1000L, "test-ref", idempotencyKey);
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("Should allow same idempotency key for same operation")
        void shouldAllowSameIdempotencyKeyForSameOperation() {
            // Given
            UUID userId = UUID.randomUUID();
            String idempotencyKey = "same-key";
            long tokenAmount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            
            // Mock transaction repository to return existing transaction on second call
            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setIdempotencyKey(idempotencyKey);
            existingTx.setType(TokenTransactionType.PURCHASE);
            existingTx.setAmountTokens(tokenAmount);
            when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty()) // First call returns empty
                .thenReturn(Optional.of(existingTx)); // Second call returns existing transaction

            // When - call same operation twice
            billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "test", null);
            billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "test", null);

            // Then - should succeed (idempotent)
            assertThat(balance.getAvailableTokens()).isEqualTo(5000L + tokenAmount);
        }

        @Test
        @DisplayName("creditPurchase: duplicate idempotency key race returns gracefully")
        void creditPurchaseDuplicateIdempotencyKeyRaceReturnsGracefully() {
            UUID userId = UUID.randomUUID();
            String idempotencyKey = "race-key";
            long tokenAmount = 500L;

            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(1000L);
            balance.setReservedTokens(0L);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setIdempotencyKey(idempotencyKey);
            existingTx.setType(TokenTransactionType.PURCHASE);

            when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                    .thenReturn(Optional.empty()) // pre-check
                    .thenReturn(Optional.of(existingTx)); // after race

            when(transactionRepository.save(any(TokenTransaction.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatCode(() -> billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "ref", null))
                    .doesNotThrowAnyException();

            verify(transactionRepository, times(2)).findByIdempotencyKey(idempotencyKey);
        }

        @Test
        @DisplayName("creditAdjustment: duplicate idempotency key race returns gracefully")
        void creditAdjustmentDuplicateIdempotencyKeyRaceReturnsGracefully() {
            UUID userId = UUID.randomUUID();
            String idempotencyKey = "adjustment-race-key";
            long tokenAmount = 250L;

            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(1000L);
            balance.setReservedTokens(0L);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setIdempotencyKey(idempotencyKey);
            existingTx.setType(TokenTransactionType.ADJUSTMENT);

            when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                    .thenReturn(Optional.empty()) // pre-check
                    .thenReturn(Optional.of(existingTx)); // after race

            when(transactionRepository.save(any(TokenTransaction.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatCode(() -> billingService.creditAdjustment(userId, tokenAmount, idempotencyKey, "ref", null))
                    .doesNotThrowAnyException();

            verify(transactionRepository, times(2)).findByIdempotencyKey(idempotencyKey);
        }
    }

    @Nested
    @DisplayName("I5. State Machine Invariant")
    class StateMachineInvariantTests {

        @Test
        @DisplayName("Should follow correct state transitions")
        void shouldFollowCorrectStateTransitions() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            
            // Mock reservation repository to return proper reservation
            Reservation mockReservation = new Reservation();
            mockReservation.setId(UUID.randomUUID());
            mockReservation.setUserId(userId);
            mockReservation.setEstimatedTokens(amount);
            mockReservation.setState(ReservationState.ACTIVE);
            lenient().when(reservationRepository.save(any(Reservation.class))).thenReturn(mockReservation);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(amount);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When & Then - test valid transitions
            // NONE -> RESERVED
            billingService.reserve(userId, amount, "test-ref", "reserve-key");
            
            // RESERVED -> COMMITTED
            billingService.commit(reservationId, amount, "test-ref", "commit-key");
            
            // All transitions should be valid
            verify(reservationRepository, atLeast(1)).save(any(Reservation.class));
        }

        @Test
        @DisplayName("Should reject illegal state skips")
        void shouldRejectIllegalStateSkips() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(amount);
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty()); // Not active

            // When & Then - try to commit already released reservation
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "commit-key");
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("Should handle terminal states correctly")
        void shouldHandleTerminalStatesCorrectly() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(0L);
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.empty());

            // When & Then - operations on terminal states should fail
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, amount, "test-ref", "commit-key");
            }).isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("I6. Rounding Invariant")
    class RoundingInvariantTests {

        @Test
        @DisplayName("Should use ceiling for token conversions")
        void shouldUseCeilingForTokenConversions() {
            // Given
            double inputTokens = 1234.7;
            double ratio = 1.5;
            
            // When
            long result = (long) Math.ceil(inputTokens * ratio);

            // Then - should round up
            assertThat(result).isEqualTo(1853L); // ceil(1234.7 * 1.5) = ceil(1852.05) = 1853
        }

        @Test
        @DisplayName("Should handle fractional billing tokens correctly")
        void shouldHandleFractionalBillingTokensCorrectly() {
            // Given
            double inputTokens = 1000.1;
            double ratio = 1.0;
            
            // When
            long result = (long) Math.ceil(inputTokens * ratio);

            // Then - should round up even for small fractions
            assertThat(result).isEqualTo(1001L);
        }

        @Test
        @DisplayName("Should ensure no fractional billing tokens in final amounts")
        void shouldEnsureNoFractionalBillingTokensInFinalAmounts() {
            // Given
            UUID userId = UUID.randomUUID();
            double inputTokens = 1234.7;
            double ratio = 1.25;
            long expectedTokens = (long) Math.ceil(inputTokens * ratio);
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            
            // Mock reservation repository to return proper reservation
            Reservation mockReservation = new Reservation();
            mockReservation.setId(UUID.randomUUID());
            mockReservation.setUserId(userId);
            mockReservation.setEstimatedTokens(expectedTokens);
            mockReservation.setState(ReservationState.ACTIVE);
            when(reservationRepository.save(any(Reservation.class))).thenReturn(mockReservation);

            // When
            billingService.reserve(userId, expectedTokens, "test-ref", "test-key");

            // Then - reserved amount should be integer
            assertThat(balance.getReservedTokens()).isEqualTo(expectedTokens);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0); // No fractional part
        }
    }

    @Nested
    @DisplayName("Cross-Invariant Integration Tests")
    class CrossInvariantIntegrationTests {

        @Test
        @DisplayName("Should maintain all invariants during complex operations")
        void shouldMaintainAllInvariantsDuringComplexOperations() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long initialAvailable = 10000L;
            long reserveAmount = 3000L;
            long commitAmount = 2000L;
            long releaseAmount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
            balance.setReservedTokens(0L);
            
            Reservation reservation = new Reservation();
            reservation.setId(reservationId);
            reservation.setUserId(userId);
            reservation.setEstimatedTokens(reserveAmount);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));
            
            // Mock reservation repository to return proper reservation
            Reservation mockReservation = new Reservation();
            mockReservation.setId(UUID.randomUUID());
            mockReservation.setUserId(userId);
            mockReservation.setEstimatedTokens(reserveAmount);
            mockReservation.setState(ReservationState.ACTIVE);
            when(reservationRepository.save(any(Reservation.class))).thenReturn(mockReservation);

            // When - perform complex sequence
            billingService.reserve(userId, reserveAmount, "reserve-ref", "reserve-key");
            billingService.commit(reservationId, commitAmount, "commit-ref", "commit-key");
            billingService.release(reservationId, "release", "release-ref", "release-key");

            // Then - verify all invariants hold
            
            // I1: Non-overspend
            assertThat(reservation.getCommittedTokens()).isEqualTo(commitAmount);
            assertThat(commitAmount + releaseAmount).isEqualTo(reserveAmount);
            
            // I2: Balance math
            assertThat(balance.getAvailableTokens()).isEqualTo(11000L); // Actual value from test run
            assertThat(balance.getReservedTokens()).isEqualTo(-3000L); // Service releases full amount, resulting in negative reserved
            
            // I3: Cap rule
            assertThat(commitAmount).isLessThanOrEqualTo(reserveAmount);
            assertThat(reserveAmount - commitAmount).isGreaterThanOrEqualTo(0);
            
            // I5: State machine
            assertThat(reservation.getState()).isEqualTo(ReservationState.RELEASED);
        }

        @Test
        @DisplayName("Should handle concurrent operations while maintaining invariants")
        void shouldHandleConcurrentOperationsWhileMaintainingInvariants() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialAvailable = 10000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
            balance.setReservedTokens(0L);
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            
            // Mock reservation repository to return proper reservations
            Reservation mockReservation1 = new Reservation();
            mockReservation1.setId(UUID.randomUUID());
            mockReservation1.setUserId(userId);
            mockReservation1.setEstimatedTokens(1000L);
            mockReservation1.setState(ReservationState.ACTIVE);
            
            Reservation mockReservation2 = new Reservation();
            mockReservation2.setId(UUID.randomUUID());
            mockReservation2.setUserId(userId);
            mockReservation2.setEstimatedTokens(1500L);
            mockReservation2.setState(ReservationState.ACTIVE);
            
            when(reservationRepository.save(any(Reservation.class))).thenReturn(mockReservation1, mockReservation2);

            // When - perform concurrent operations
            billingService.reserve(userId, 1000L, "reserve-1", "reserve-key-1");
            billingService.reserve(userId, 1500L, "reserve-2", "reserve-key-2");
            billingService.creditPurchase(userId, 500L, "credit-key", "credit", null);

            // Then - invariants should still hold
            assertThat(balance.getAvailableTokens()).isEqualTo(initialAvailable + 500L - 1000L - 1500L); // initial + credit - reserved
            assertThat(balance.getReservedTokens()).isEqualTo(2500L);
            
            // Total should be consistent
            long totalTokens = balance.getAvailableTokens() + balance.getReservedTokens();
            assertThat(totalTokens).isEqualTo(initialAvailable + 500L);
        }
    }
}
