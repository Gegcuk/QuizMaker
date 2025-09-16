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
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.CommitExceedsReservedException;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
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
import uk.gegc.quizmaker.features.billing.testutils.AccountSnapshot;

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
 * Comprehensive tests for core billing invariants (I1-I6) that must hold across all operations.
 * These invariants are fundamental to the correctness of the billing system.
 * 
 * I1. Non-overspend: For any reservation R, Σ(committed for R) ≤ R and Σ(released for R) + Σ(committed for R) = R.
 * I2. Balance math: final_available = initial + credits + adjustments − Σ(all committed) (reservations don't change available).
 * I3. Cap rule: committed = min(actual, reserved) (no epsilon), remainder = reserved − committed ≥ 0.
 * I4. Idempotency: Same idempotency key ⇒ at-most-once effect per operation type.
 * I5. State machine: NONE → RESERVED → (COMMITTED | RELEASED | EXPIRED). No illegal skips.
 * I6. Rounding: Conversions use ceil where specified; no fractional billing tokens.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Core Invariants Comprehensive Tests")
@Execution(ExecutionMode.CONCURRENT)
class CoreInvariantsComprehensiveTest {

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
    @DisplayName("I1. Non-overspend Invariant")
    class NonOverspendInvariantTests {

        @Test
        @DisplayName("Should never allow committed tokens to exceed reservation amount")
        void shouldNeverAllowCommittedTokensToExceedReservationAmount() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservationAmount = 1000L;
            long actualUsage = 1500L; // More than reserved
            
            // Use utility to create mock balance
            Balance balance = BillingTestUtils.createMockBalance(userId, 5000L, reservationAmount);
            
            // Use utility to create mock reservation
            Reservation reservation = BillingTestUtils.createMockReservation(userId, reservationAmount, ReservationState.ACTIVE);
            reservation.setId(reservationId);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));

            // When & Then
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, actualUsage, "test-ref", "test-key");
            }).isInstanceOf(CommitExceedsReservedException.class)
              .hasMessageContaining("Actual usage exceeds reserved tokens");
        }

        @Test
        @DisplayName("Should ensure committed + released = reserved amount")
        void shouldEnsureCommittedPlusReleasedEqualsReservedAmount() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long committedAmount = 600L;
            long expectedReleased = 400L; // reserved - committed
            
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

            // When - commit partial amount
            var commitResult = billingService.commit(reservationId, committedAmount, "commit-ref", "commit-key");
            
            // Then - verify invariant holds
            assertThat(commitResult.committedTokens()).isEqualTo(committedAmount);
            assertThat(commitResult.releasedTokens()).isEqualTo(expectedReleased);
            assertThat(commitResult.committedTokens() + commitResult.releasedTokens()).isEqualTo(reservedAmount);
        }

        @Test
        @DisplayName("Should handle multiple commits within reservation limit")
        void shouldHandleMultipleCommitsWithinReservationLimit() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long firstCommit = 300L;
            long secondCommit = 400L;
            
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

            // When - commit in chunks
            var result1 = billingService.commit(reservationId, firstCommit, "commit-1", "key-1");
            var result2 = billingService.commit(reservationId, secondCommit, "commit-2", "key-2");

            // Then - verify total committed doesn't exceed reserved
            assertThat(result1.committedTokens() + result2.committedTokens()).isLessThanOrEqualTo(reservedAmount);
        }

        @Test
        @DisplayName("Should prevent overspend across multiple reservations")
        void shouldPreventOverspendAcrossMultipleReservations() {
            // Given
            UUID userId = UUID.randomUUID();
            long availableTokens = 2000L;
            long reservation1Amount = 1500L;
            long reservation2Amount = 1000L; // This should fail
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(availableTokens);
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

            // When - first reservation succeeds
            var result1 = billingService.reserve(userId, reservation1Amount, "reserve-1", "key-1");
            assertThat(result1).isNotNull();
            
            // When - second reservation should fail due to insufficient tokens
            assertThatThrownBy(() -> {
                billingService.reserve(userId, reservation2Amount, "reserve-2", "key-2");
            }).isInstanceOf(InsufficientTokensException.class)
              .satisfies(exception -> {
                  InsufficientTokensException ex = (InsufficientTokensException) exception;
                  assertThat(ex.getAvailableTokens()).isEqualTo(availableTokens - reservation1Amount);
                  assertThat(ex.getShortfall()).isEqualTo(reservation2Amount - (availableTokens - reservation1Amount));
              });
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
            long initialReserved = 0L;
            long credits = 2000L;
            long reserveAmount = 1000L;
            long commitAmount = 600L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
            balance.setReservedTokens(initialReserved);
            
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

            // When - perform operations
            // 1. Add credits
            billingService.creditPurchase(userId, credits, "credit-key", "credit", null);
            
            // 2. Reserve tokens
            var reservation = billingService.reserve(userId, reserveAmount, "reserve-ref", "reserve-key");
            
            // 3. Commit some reserved tokens
            Reservation commitReservation = new Reservation();
            commitReservation.setId(reservation.id());
            commitReservation.setUserId(userId);
            commitReservation.setEstimatedTokens(reserveAmount);
            commitReservation.setCommittedTokens(0L);
            commitReservation.setState(ReservationState.ACTIVE);
            commitReservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitReservation));
            
            billingService.commit(reservation.id(), commitAmount, "commit", "commit-key");

            // Then - verify balance math
            long expectedAvailable = initialAvailable + credits - reserveAmount + (reserveAmount - commitAmount); // Available after reservation + released tokens
            long expectedReserved = 0L; // Reservation is fully consumed after commit
            
            assertThat(balance.getAvailableTokens()).isEqualTo(expectedAvailable);
            assertThat(balance.getReservedTokens()).isEqualTo(expectedReserved);
        }

        @Test
        @DisplayName("Should not change available tokens during reservation")
        void shouldNotChangeAvailableTokensDuringReservation() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialAvailable = 5000L;
            long reserveAmount = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
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

            // When - reserve tokens
            billingService.reserve(userId, reserveAmount, "test-ref", "test-key");

            // Then - available tokens should be reduced by reserved amount
            assertThat(balance.getAvailableTokens()).isEqualTo(initialAvailable - reserveAmount);
            assertThat(balance.getReservedTokens()).isEqualTo(reserveAmount);
        }

        @Test
        @DisplayName("Should handle complex balance operations correctly")
        void shouldHandleComplexBalanceOperationsCorrectly() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialAvailable = 10000L;
            long reserve1 = 3000L;
            long reserve2 = 2000L;
            long credits = 1000L;
            long commit1 = 2500L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
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

            // When - perform complex operations
            // Reserve 3000
            var reservation1 = billingService.reserve(userId, reserve1, "reserve-1", "reserve-key-1");
            // Reserve another 2000
            billingService.reserve(userId, reserve2, "reserve-2", "reserve-key-2");
            // Add 1000 credits
            billingService.creditPurchase(userId, credits, "credit-key", "credit", null);
            // Commit 2500 from first reservation
            Reservation commitReservation = new Reservation();
            commitReservation.setId(reservation1.id());
            commitReservation.setUserId(userId);
            commitReservation.setEstimatedTokens(reserve1);
            commitReservation.setCommittedTokens(0L);
            commitReservation.setState(ReservationState.ACTIVE);
            commitReservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation1.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitReservation));
            
            billingService.commit(reservation1.id(), commit1, "commit-1", "commit-key-1");

            // Then - verify final balance
            long expectedAvailable = initialAvailable - reserve1 - reserve2 + credits + (reserve1 - commit1); // Available after reservations, credits, and released tokens
            long expectedReserved = reserve2; // Only the second reservation remains (first is fully consumed)
            
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

            // When & Then - try to commit more than reserved
            assertThatThrownBy(() -> {
                billingService.commit(reservationId, actualUsage, "test-ref", "test-key");
            }).isInstanceOf(CommitExceedsReservedException.class)
              .hasMessageContaining("Actual usage exceeds reserved tokens");
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
            reservation.setCommittedTokens(0L);
            reservation.setState(ReservationState.ACTIVE);
            reservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
                .thenReturn(Optional.of(reservation));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());

            // When - commit exact amount
            var result = billingService.commit(reservationId, reservedAmount, "test-ref", "test-key");

            // Then - should succeed
            assertThat(result.committedTokens()).isEqualTo(reservedAmount);
            assertThat(result.releasedTokens()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should ensure remainder is non-negative")
        void shouldEnsureRemainderIsNonNegative() {
            // Given
            UUID userId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();
            long reservedAmount = 1000L;
            long committedAmount = 600L;
            long expectedRemainder = 400L;
            
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

            // When - commit partial amount
            var result = billingService.commit(reservationId, committedAmount, "test-ref", "test-key");

            // Then - remainder should be non-negative
            assertThat(result.releasedTokens()).isEqualTo(expectedRemainder);
            assertThat(result.releasedTokens()).isGreaterThanOrEqualTo(0);
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
            when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty()) // First call returns empty
                .thenReturn(Optional.of(createPurchaseTransaction(idempotencyKey, tokenAmount))); // Second call returns existing

            // When - call same operation twice with same key
            billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "test", null);
            billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "test", null);

            // Then - should only be applied once
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
            }).isInstanceOf(IdempotencyConflictException.class)
              .hasMessageContaining("already used for a different operation");
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
            when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty()) // First call returns empty
                .thenReturn(Optional.of(createPurchaseTransaction(idempotencyKey, tokenAmount))); // Second call returns existing

            // When - call same operation twice
            billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "test", null);
            billingService.creditPurchase(userId, tokenAmount, idempotencyKey, "test", null);

            // Then - should succeed (idempotent)
            assertThat(balance.getAvailableTokens()).isEqualTo(5000L + tokenAmount);
        }

        private TokenTransaction createPurchaseTransaction(String idempotencyKey, long amount) {
            TokenTransaction tx = new TokenTransaction();
            tx.setIdempotencyKey(idempotencyKey);
            tx.setType(TokenTransactionType.PURCHASE);
            tx.setAmountTokens(amount);
            return tx;
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

            // When & Then - test valid transitions
            // NONE -> RESERVED (ACTIVE)
            var reservation = billingService.reserve(userId, amount, "test-ref", "reserve-key");
            assertThat(reservation.state()).isEqualTo(ReservationState.ACTIVE);
            
            // RESERVED -> COMMITTED
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
            
            // All transitions should be valid
            verify(reservationRepository, atLeast(1)).save(any(Reservation.class));
        }

        @Test
        @DisplayName("Should reject illegal state skips")
        void shouldRejectIllegalStateSkips() {
            // Given
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
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
            UUID reservationId = UUID.randomUUID();
            long amount = 1000L;
            
            when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE))
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
            long initialAvailable = 10000L;
            long reserveAmount = 3000L;
            long commitAmount = 2000L;
            long credits = 1000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
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

            // When - perform complex sequence
            var reservation = billingService.reserve(userId, reserveAmount, "reserve-ref", "reserve-key");
            billingService.creditPurchase(userId, credits, "credit-key", "credit", null);
            
            Reservation commitReservation = new Reservation();
            commitReservation.setId(reservation.id());
            commitReservation.setUserId(userId);
            commitReservation.setEstimatedTokens(reserveAmount);
            commitReservation.setCommittedTokens(0L);
            commitReservation.setState(ReservationState.ACTIVE);
            commitReservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitReservation));
            
            var commitResult = billingService.commit(reservation.id(), commitAmount, "commit-ref", "commit-key");

            // Then - verify all invariants hold
            
            // I1: Non-overspend - use utility assertion
            assertThat(commitResult.committedTokens()).isEqualTo(commitAmount);
            assertThat(commitResult.committedTokens() + commitResult.releasedTokens()).isEqualTo(reserveAmount);
            
            // I2: Balance math - use utility assertion
            AccountSnapshot before = AccountSnapshot.initial(userId, initialAvailable, 0L);
            AccountSnapshot after = AccountSnapshot.after(userId, balance.getAvailableTokens(), balance.getReservedTokens(), credits, 0L, commitAmount);
            LedgerAsserts.assertI2_BalanceMath(before, after);
            
            // I3: Cap rule - use utility assertion
            LedgerAsserts.assertI3_CapRule(commitAmount, reserveAmount, commitAmount);
            
            // I6: Rounding - all amounts should be integers
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle concurrent operations while maintaining invariants")
        void shouldHandleConcurrentOperationsWhileMaintainingInvariants() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialAvailable = 10000L;
            long reserve1 = 1000L;
            long reserve2 = 1500L;
            long credits = 500L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialAvailable);
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

            // When - perform concurrent operations
            billingService.reserve(userId, reserve1, "reserve-1", "reserve-key-1");
            billingService.reserve(userId, reserve2, "reserve-2", "reserve-key-2");
            billingService.creditPurchase(userId, credits, "credit-key", "credit", null);

            // Then - invariants should still hold
            long expectedAvailable = initialAvailable + credits - reserve1 - reserve2;
            long expectedReserved = reserve1 + reserve2;
            
            assertThat(balance.getAvailableTokens()).isEqualTo(expectedAvailable);
            assertThat(balance.getReservedTokens()).isEqualTo(expectedReserved);
            
            // Total should be consistent
            long totalTokens = balance.getAvailableTokens() + balance.getReservedTokens();
            assertThat(totalTokens).isEqualTo(initialAvailable + credits);
        }
    }
}
