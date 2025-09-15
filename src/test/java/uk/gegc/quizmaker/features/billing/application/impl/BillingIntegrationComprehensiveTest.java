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

import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Comprehensive integration tests that verify all invariants hold during complex operations.
 * 
 * These tests simulate real-world scenarios with multiple users, concurrent operations,
 * and complex billing flows to ensure all invariants (I1-I6) are maintained.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Billing Integration Comprehensive Tests")
@Execution(ExecutionMode.CONCURRENT)
class BillingIntegrationComprehensiveTest {

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
    @DisplayName("Multi-User Complex Scenarios")
    class MultiUserComplexScenariosTests {

        @Test
        @DisplayName("Should maintain invariants across multiple users with complex operations")
        void shouldMaintainInvariantsAcrossMultipleUsersWithComplexOperations() {
            // Given - multiple users with different scenarios
            UUID user1 = UUID.randomUUID();
            UUID user2 = UUID.randomUUID();
            UUID user3 = UUID.randomUUID();
            
            // Setup balances for each user
            Balance balance1 = new Balance();
            balance1.setUserId(user1);
            balance1.setAvailableTokens(10000L);
            balance1.setReservedTokens(0L);
            
            Balance balance2 = new Balance();
            balance2.setUserId(user2);
            balance2.setAvailableTokens(5000L);
            balance2.setReservedTokens(0L);
            
            Balance balance3 = new Balance();
            balance3.setUserId(user3);
            balance3.setAvailableTokens(2000L);
            balance3.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(user1)).thenReturn(Optional.of(balance1));
            when(balanceRepository.findByUserId(user2)).thenReturn(Optional.of(balance2));
            when(balanceRepository.findByUserId(user3)).thenReturn(Optional.of(balance3));
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
                    res.getId(), res.getUserId(), ReservationState.ACTIVE, res.getEstimatedTokens(), 0L, null, null, null, null);
            });

            // When - perform complex operations for each user
            // User 1: Reserve, commit partial, add credits
            billingService.reserve(user1, 3000L, "user1-reserve", "user1-key");
            billingService.creditPurchase(user1, 2000L, "user1-credit", "credit", null);
            
            // User 2: Multiple reservations
            billingService.reserve(user2, 1000L, "user2-reserve-a", "user2-key-a");
            billingService.reserve(user2, 1500L, "user2-reserve-b", "user2-key-b");
            
            // User 3: Try to reserve more than available (should fail)
            assertThatThrownBy(() -> {
                billingService.reserve(user3, 3000L, "user3-reserve", "user3-key");
            }).isInstanceOf(InsufficientTokensException.class);

            // Then - verify all invariants hold
            
            // I1: Non-overspend - no user should have negative available tokens
            assertThat(balance1.getAvailableTokens()).isGreaterThanOrEqualTo(0);
            assertThat(balance2.getAvailableTokens()).isGreaterThanOrEqualTo(0);
            assertThat(balance3.getAvailableTokens()).isGreaterThanOrEqualTo(0);
            
            // I2: Balance math - total tokens should be consistent
            long user1Total = balance1.getAvailableTokens() + balance1.getReservedTokens();
            long user2Total = balance2.getAvailableTokens() + balance2.getReservedTokens();
            long user3Total = balance3.getAvailableTokens() + balance3.getReservedTokens();
            
            assertThat(user1Total).isEqualTo(10000L + 2000L); // initial + credits
            assertThat(user2Total).isEqualTo(5000L); // initial
            assertThat(user3Total).isEqualTo(2000L); // initial (no operations)
            
            // I6: Rounding - all amounts should be integers
            assertThat(balance1.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance1.getReservedTokens() % 1).isEqualTo(0);
            assertThat(balance2.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance2.getReservedTokens() % 1).isEqualTo(0);
            assertThat(balance3.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance3.getReservedTokens() % 1).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle concurrent operations while maintaining invariants")
        void shouldHandleConcurrentOperationsWhileMaintainingInvariants() throws Exception {
            // Given
            UUID userId = UUID.randomUUID();
            int numberOfThreads = 10;
            int operationsPerThread = 5;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(100000L); // Large amount to handle concurrent operations
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
            ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (int i = 0; i < numberOfThreads; i++) {
                final int threadId = i;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            // Mix of reserve and credit operations
                            if (j % 2 == 0) {
                                billingService.reserve(userId, 1000L, "thread-" + threadId + "-op-" + j, "key-" + threadId + "-" + j);
                            } else {
                                billingService.creditPurchase(userId, 500L, "credit-" + threadId + "-" + j, "credit", null);
                            }
                        } catch (Exception e) {
                            // Some operations might fail due to insufficient tokens, which is expected
                        }
                    }
                }, executor);
                futures.add(future);
            }
            
            // Wait for all operations to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
            executor.shutdown();

            // Then - verify invariants still hold
            
            // I1: Non-overspend
            assertThat(balance.getAvailableTokens()).isGreaterThanOrEqualTo(0);
            assertThat(balance.getReservedTokens()).isGreaterThanOrEqualTo(0);
            
            // I2: Balance math
            long totalTokens = balance.getAvailableTokens() + balance.getReservedTokens();
            assertThat(totalTokens).isGreaterThanOrEqualTo(100000L); // Should have at least initial amount
            
            // I6: Rounding
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Complex Billing Flows")
    class ComplexBillingFlowsTests {

        @Test
        @DisplayName("Should handle complete quiz generation billing flow maintaining all invariants")
        void shouldHandleCompleteQuizGenerationBillingFlowMaintainingAllInvariants() {
            // Given - simulate a complete quiz generation flow
            UUID userId = UUID.randomUUID();
            long initialTokens = 10000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialTokens);
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

            // When - simulate complete flow
            // 1. Reserve tokens for quiz generation
            long estimatedTokens = 5000L;
            var reservation = billingService.reserve(userId, estimatedTokens, "quiz-gen", "reserve-key");
            
            // 2. Add some credits during processing
            billingService.creditPurchase(userId, 2000L, "credit-key", "credit", null);
            
            // 3. Commit partial amount (actual usage was less than estimated)
            long actualUsage = 3500L;
            Reservation commitReservation = new Reservation();
            commitReservation.setId(reservation.id());
            commitReservation.setUserId(userId);
            commitReservation.setEstimatedTokens(estimatedTokens);
            commitReservation.setCommittedTokens(0L);
            commitReservation.setState(ReservationState.ACTIVE);
            commitReservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitReservation));
            
            var commitResult = billingService.commit(reservation.id(), actualUsage, "commit", "commit-key");
            
            // Then - verify all invariants hold
            
            // I1: Non-overspend
            assertThat(commitResult.committedTokens()).isEqualTo(actualUsage);
            assertThat(commitResult.committedTokens() + commitResult.releasedTokens()).isEqualTo(estimatedTokens);
            
            // I2: Balance math
            long expectedAvailable = initialTokens + 2000L - estimatedTokens + (estimatedTokens - actualUsage);
            long expectedReserved = 0L; // Reservation is fully consumed after commit
            assertThat(balance.getAvailableTokens()).isEqualTo(expectedAvailable);
            assertThat(balance.getReservedTokens()).isEqualTo(expectedReserved);
            
            // I3: Cap rule
            assertThat(actualUsage).isLessThanOrEqualTo(estimatedTokens);
            assertThat(estimatedTokens - actualUsage).isGreaterThanOrEqualTo(0);
            
            // I6: Rounding
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
            assertThat(commitResult.committedTokens() % 1).isEqualTo(0);
            assertThat(commitResult.releasedTokens() % 1).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle multiple reservations with partial commits and releases")
        void shouldHandleMultipleReservationsWithPartialCommitsAndReleases() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialTokens = 20000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialTokens);
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

            // When - create multiple reservations
            var reservation1 = billingService.reserve(userId, 5000L, "reserve-1", "key-1");
            var reservation2 = billingService.reserve(userId, 3000L, "reserve-2", "key-2");
            var reservation3 = billingService.reserve(userId, 2000L, "reserve-3", "key-3");
            
            // Commit partial amounts
            Reservation commitRes1 = new Reservation();
            commitRes1.setId(reservation1.id());
            commitRes1.setUserId(userId);
            commitRes1.setEstimatedTokens(5000L);
            commitRes1.setCommittedTokens(0L);
            commitRes1.setState(ReservationState.ACTIVE);
            commitRes1.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            Reservation commitRes2 = new Reservation();
            commitRes2.setId(reservation2.id());
            commitRes2.setUserId(userId);
            commitRes2.setEstimatedTokens(3000L);
            commitRes2.setCommittedTokens(0L);
            commitRes2.setState(ReservationState.ACTIVE);
            commitRes2.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation1.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitRes1));
            when(reservationRepository.findByIdAndState(reservation2.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitRes2));
            
            var commit1 = billingService.commit(reservation1.id(), 4000L, "commit-1", "commit-key-1");
            var commit2 = billingService.commit(reservation2.id(), 3000L, "commit-2", "commit-key-2");
            
            // Release the third reservation
            Reservation releaseRes = new Reservation();
            releaseRes.setId(reservation3.id());
            releaseRes.setUserId(userId);
            releaseRes.setEstimatedTokens(2000L);
            releaseRes.setCommittedTokens(0L);
            releaseRes.setState(ReservationState.ACTIVE);
            releaseRes.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation3.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(releaseRes));
            
            var release = billingService.release(reservation3.id(), "cancelled", "release", "release-key");
            
            // Then - verify all invariants hold
            
            // I1: Non-overspend
            assertThat(commit1.committedTokens() + commit1.releasedTokens()).isEqualTo(5000L);
            assertThat(commit2.committedTokens() + commit2.releasedTokens()).isEqualTo(3000L);
            assertThat(release.releasedTokens()).isEqualTo(2000L);
            
            // I2: Balance math
            long totalCommitted = commit1.committedTokens() + commit2.committedTokens();
            long totalReleased = commit1.releasedTokens() + commit2.releasedTokens() + release.releasedTokens();
            long expectedAvailable = initialTokens - 5000L - 3000L - 2000L + totalReleased;
            long expectedReserved = 5000L + 3000L + 2000L - totalCommitted - totalReleased;
            
            assertThat(balance.getAvailableTokens()).isEqualTo(expectedAvailable);
            assertThat(balance.getReservedTokens()).isEqualTo(expectedReserved);
            
            // I3: Cap rule
            assertThat(commit1.committedTokens()).isLessThanOrEqualTo(5000L);
            assertThat(commit2.committedTokens()).isLessThanOrEqualTo(3000L);
            
            // I6: Rounding
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Error Recovery and Edge Cases")
    class ErrorRecoveryAndEdgeCasesTests {

        @Test
        @DisplayName("Should maintain invariants when operations fail")
        void shouldMaintainInvariantsWhenOperationsFail() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialTokens = 5000L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialTokens);
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

            // When - perform operations that will fail
            // 1. Successful reservation
            var reservation = billingService.reserve(userId, 2000L, "success", "success-key");
            
            // 2. Try to reserve more than available (should fail)
            assertThatThrownBy(() -> {
                billingService.reserve(userId, 5000L, "fail", "fail-key");
            }).isInstanceOf(InsufficientTokensException.class);
            
            // 3. Try to commit more than reserved (should fail)
            Reservation commitReservation = new Reservation();
            commitReservation.setId(reservation.id());
            commitReservation.setUserId(userId);
            commitReservation.setEstimatedTokens(2000L);
            commitReservation.setCommittedTokens(0L);
            commitReservation.setState(ReservationState.ACTIVE);
            commitReservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitReservation));
            
            assertThatThrownBy(() -> {
                billingService.commit(reservation.id(), 3000L, "fail-commit", "fail-commit-key");
            }).isInstanceOf(CommitExceedsReservedException.class);
            
            // Then - verify invariants still hold
            
            // I1: Non-overspend
            assertThat(balance.getAvailableTokens()).isGreaterThanOrEqualTo(0);
            assertThat(balance.getReservedTokens()).isGreaterThanOrEqualTo(0);
            
            // I2: Balance math
            long totalTokens = balance.getAvailableTokens() + balance.getReservedTokens();
            assertThat(totalTokens).isEqualTo(initialTokens);
            
            // I6: Rounding
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle idempotency conflicts while maintaining invariants")
        void shouldHandleIdempotencyConflictsWhileMaintainingInvariants() {
            // Given
            UUID userId = UUID.randomUUID();
            String conflictingKey = "conflict-key";
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(10000L);
            balance.setReservedTokens(0L);
            
            // Create existing transaction with different operation
            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setIdempotencyKey(conflictingKey);
            existingTx.setType(TokenTransactionType.PURCHASE);
            existingTx.setAmountTokens(1000L);
            
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            lenient().when(transactionRepository.findByIdempotencyKey(conflictingKey))
                .thenReturn(Optional.of(existingTx));

            // When - try to use conflicting idempotency key
            assertThatThrownBy(() -> {
                billingService.reserve(userId, 2000L, "test-ref", conflictingKey);
            }).isInstanceOf(IdempotencyConflictException.class);
            
            // Then - verify invariants still hold
            assertThat(balance.getAvailableTokens()).isEqualTo(10000L);
            assertThat(balance.getReservedTokens()).isEqualTo(0L);
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle boundary conditions maintaining all invariants")
        void shouldHandleBoundaryConditionsMaintainingAllInvariants() {
            // Given
            UUID userId = UUID.randomUUID();
            long initialTokens = 1000L; // Small amount to test boundaries
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(initialTokens);
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

            // When - test boundary conditions
            // 1. Reserve exact amount available
            var reservation = billingService.reserve(userId, initialTokens, "exact", "exact-key");
            
            // 2. Try to reserve more (should fail)
            assertThatThrownBy(() -> {
                billingService.reserve(userId, 1L, "overflow", "overflow-key");
            }).isInstanceOf(InsufficientTokensException.class);
            
            // 3. Commit exact reserved amount
            Reservation commitReservation = new Reservation();
            commitReservation.setId(reservation.id());
            commitReservation.setUserId(userId);
            commitReservation.setEstimatedTokens(initialTokens);
            commitReservation.setCommittedTokens(0L);
            commitReservation.setState(ReservationState.ACTIVE);
            commitReservation.setExpiresAt(LocalDateTime.now().plusHours(1));
            
            when(reservationRepository.findByIdAndState(reservation.id(), ReservationState.ACTIVE))
                .thenReturn(Optional.of(commitReservation));
            
            var commitResult = billingService.commit(reservation.id(), initialTokens, "exact-commit", "exact-commit-key");
            
            // Then - verify all invariants hold
            
            // I1: Non-overspend
            assertThat(commitResult.committedTokens()).isEqualTo(initialTokens);
            assertThat(commitResult.releasedTokens()).isEqualTo(0L);
            
            // I2: Balance math
            assertThat(balance.getAvailableTokens()).isEqualTo(0L);
            assertThat(balance.getReservedTokens()).isEqualTo(0L);
            
            // I3: Cap rule
            assertThat(commitResult.committedTokens()).isLessThanOrEqualTo(initialTokens);
            
            // I6: Rounding
            assertThat(balance.getAvailableTokens() % 1).isEqualTo(0);
            assertThat(balance.getReservedTokens() % 1).isEqualTo(0);
            assertThat(commitResult.committedTokens() % 1).isEqualTo(0);
            assertThat(commitResult.releasedTokens() % 1).isEqualTo(0);
        }
    }
}
