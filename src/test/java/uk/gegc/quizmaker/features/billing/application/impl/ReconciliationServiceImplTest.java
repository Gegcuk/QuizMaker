package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.ReconciliationService;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationService Tests")
class ReconciliationServiceImplTest {

    @Mock
    private BalanceRepository balanceRepository;
    
    @Mock
    private TokenTransactionRepository transactionRepository;
    
    @Mock
    private BillingMetricsService metricsService;

    private ReconciliationServiceImpl reconciliationService;

    @BeforeEach
    void setup() {
        reconciliationService = new ReconciliationServiceImpl(balanceRepository, transactionRepository, metricsService);
    }

    @Test
    @DisplayName("reconcileUser: when REFUND transactions exist then use Math.abs for negative amounts")
    void reconcileUser_whenRefundTransactionsExist_thenUseMathAbsForNegativeAmounts() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 300 available, 0 reserved
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(300L);
        balance.setReservedTokens(0L);
        
        // Create transactions: 1000 credit, 500 commit, 200 refund (stored as -200)
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction commit = createTransaction(TokenTransactionType.COMMIT, 500L);
        TokenTransaction refund = createTransaction(TokenTransactionType.REFUND, -200L); // Negative amount
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, commit, refund);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertNotNull(result);
        assertTrue(result.isBalanced());
        
        // Expected calculation:
        // Credits: 1000 (purchase)
        // Debits: 500 (commit) + 200 (refund, using Math.abs(-200))
        // Balance: 1000 - (500 + 200) = 300
        assertEquals(300L, result.calculatedBalance());
    }

    @Test
    @DisplayName("reconcileUser: when multiple REFUND transactions exist then aggregate correctly")
    void reconcileUser_whenMultipleRefundTransactionsExist_thenAggregateCorrectly() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 200 available, 0 reserved
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(200L);
        balance.setReservedTokens(0L);
        
        // Create transactions: 1000 credit, 500 commit, 200 refund, 100 refund
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction commit = createTransaction(TokenTransactionType.COMMIT, 500L);
        TokenTransaction refund1 = createTransaction(TokenTransactionType.REFUND, -200L); // Negative amount
        TokenTransaction refund2 = createTransaction(TokenTransactionType.REFUND, -100L); // Negative amount
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, commit, refund1, refund2);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertNotNull(result);
        assertTrue(result.isBalanced());
        
        // Expected calculation:
        // Credits: 1000 (purchase)
        // Debits: 500 (commit) + 200 (refund1, using Math.abs(-200)) + 100 (refund2, using Math.abs(-100))
        // Balance: 1000 - (500 + 200 + 100) = 200
        assertEquals(200L, result.calculatedBalance());
    }

    @Test
    @DisplayName("reconcileUser: when REFUND transactions are positive then handle correctly")
    void reconcileUser_whenRefundTransactionsArePositive_thenHandleCorrectly() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 300 available, 0 reserved
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(300L);
        balance.setReservedTokens(0L);
        
        // Create transactions: 1000 credit, 500 commit, 200 refund (positive amount - edge case)
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction commit = createTransaction(TokenTransactionType.COMMIT, 500L);
        TokenTransaction refund = createTransaction(TokenTransactionType.REFUND, 200L); // Positive amount (edge case)
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, commit, refund);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertNotNull(result);
        assertTrue(result.isBalanced());
        
        // Expected calculation:
        // Credits: 1000 (purchase)
        // Debits: 500 (commit) + 200 (refund, using Math.abs(200))
        // Balance: 1000 - (500 + 200) = 300
        assertEquals(300L, result.calculatedBalance());
    }

    @Test
    @DisplayName("reconcileUser: when no REFUND transactions then calculation works normally")
    void reconcileUser_whenNoRefundTransactions_thenCalculationWorksNormally() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 500 available, 0 reserved
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(500L);
        balance.setReservedTokens(0L);
        
        // Create transactions: 1000 credit, 500 commit (no refunds)
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction commit = createTransaction(TokenTransactionType.COMMIT, 500L);
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, commit);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertNotNull(result);
        assertTrue(result.isBalanced());
        
        // Expected calculation:
        // Credits: 1000 (purchase)
        // Debits: 500 (commit)
        // Balance: 1000 - 500 = 500
        assertEquals(500L, result.calculatedBalance());
    }

    @Test
    @DisplayName("reconcileUser: when no balance found then returns success with zero values")
    void reconcileUser_noBalanceFound_returnsSuccessWithZeros() {
        // Given
        UUID userId = UUID.randomUUID();
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.empty());

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isBalanced()).isTrue();
        assertThat(result.calculatedBalance()).isEqualTo(0);
        assertThat(result.actualBalance()).isEqualTo(0);
        assertThat(result.driftAmount()).isEqualTo(0);
        assertThat(result.details()).contains("No balance found for user");
        
        verify(balanceRepository).findByUserId(userId);
        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(metricsService);
    }

    @Test
    @DisplayName("reconcileUser: when drift detected then records drift metrics")
    void reconcileUser_driftDetected_recordsDriftMetrics() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 400 available, 0 reserved (actual = 400)
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(400L);
        balance.setReservedTokens(0L);
        
        // Create transactions that should result in 500 tokens
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction commit = createTransaction(TokenTransactionType.COMMIT, 500L);
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, commit);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isBalanced()).isFalse();
        assertThat(result.calculatedBalance()).isEqualTo(500);
        assertThat(result.actualBalance()).isEqualTo(400);
        assertThat(result.driftAmount()).isEqualTo(100); // calculated - actual
        
        verify(metricsService).recordReconciliationDrift(userId, 100L);
        verify(metricsService, never()).recordReconciliationSuccess(any());
    }

    @Test
    @DisplayName("reconcileUser: when exception occurs then records failure")
    void reconcileUser_exceptionOccurs_recordsFailure() {
        // Given
        UUID userId = UUID.randomUUID();
        when(balanceRepository.findByUserId(userId))
                .thenThrow(new RuntimeException("Database error"));

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isBalanced()).isFalse();
        assertThat(result.calculatedBalance()).isEqualTo(0);
        assertThat(result.actualBalance()).isEqualTo(0);
        assertThat(result.details()).contains("Error during reconciliation");
        assertThat(result.details()).contains("Database error");
        
        verify(metricsService).recordReconciliationFailure(eq(userId), contains("Database error"));
    }

    @Test
    @DisplayName("reconcileUser: when RESERVE transactions exist then no net change to calculated balance")
    void reconcileUser_reserveTransactions_noNetChange() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 700 available, 200 reserved = 900 total
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(700L);
        balance.setReservedTokens(200L);
        
        // Create transactions: 1000 purchase, 100 commit, 200 reserve
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction commit = createTransaction(TokenTransactionType.COMMIT, 100L);
        TokenTransaction reserve = createTransaction(TokenTransactionType.RESERVE, 200L);
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, commit, reserve);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isBalanced()).isTrue();
        // Calculation: 1000 (purchase) - 100 (commit) = 900 (RESERVE doesn't change net balance)
        // Actual: 700 available + 200 reserved = 900
        assertThat(result.calculatedBalance()).isEqualTo(900);
        assertThat(result.actualBalance()).isEqualTo(900);
        
        verify(metricsService).recordReconciliationSuccess(userId);
    }

    @Test
    @DisplayName("reconcileUser: when RELEASE transactions exist then no net change to calculated balance")
    void reconcileUser_releaseTransactions_noNetChange() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 900 available, 0 reserved = 900 total
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(900L);
        balance.setReservedTokens(0L);
        
        // Create transactions: 1000 purchase, 100 commit, 200 release
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction commit = createTransaction(TokenTransactionType.COMMIT, 100L);
        TokenTransaction release = createTransaction(TokenTransactionType.RELEASE, 200L);
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, commit, release);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isBalanced()).isTrue();
        // Calculation: 1000 (purchase) - 100 (commit) = 900 (RELEASE doesn't change net balance)
        assertThat(result.calculatedBalance()).isEqualTo(900);
        assertThat(result.actualBalance()).isEqualTo(900);
        
        verify(metricsService).recordReconciliationSuccess(userId);
    }

    @Test
    @DisplayName("reconcileUser: when negative ADJUSTMENT transaction exists then adds to debits")
    void reconcileUser_negativeAdjustment_addsToDebits() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 700 available, 0 reserved
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(700L);
        balance.setReservedTokens(0L);
        
        // Create transactions: 1000 purchase, negative 300 adjustment (deduction)
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction negativeAdjustment = createTransaction(TokenTransactionType.ADJUSTMENT, -300L);
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, negativeAdjustment);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isBalanced()).isTrue();
        // Calculation: 1000 (purchase) - 300 (negative adjustment) = 700
        assertThat(result.calculatedBalance()).isEqualTo(700);
        assertThat(result.actualBalance()).isEqualTo(700);
        
        verify(metricsService).recordReconciliationSuccess(userId);
    }

    @Nested
    @DisplayName("reconcileAllUsers Tests")
    class ReconcileAllUsersTests {

        @Test
        @DisplayName("should reconcile all users successfully when all balanced")
        void reconcileAllUsers_allBalanced_returnsSuccessfulSummary() {
            // Given
            UUID user1 = UUID.randomUUID();
            UUID user2 = UUID.randomUUID();
            
            Balance balance1 = createBalance(user1, 500L, 0L);
            Balance balance2 = createBalance(user2, 300L, 0L);
            
            when(balanceRepository.findAll()).thenReturn(Arrays.asList(balance1, balance2));
            
            // User 1: 1000 purchase - 500 commit = 500
            when(balanceRepository.findByUserId(user1)).thenReturn(Optional.of(balance1));
            when(transactionRepository.findByUserId(user1)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 1000L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));
            
            // User 2: 800 purchase - 500 commit = 300
            when(balanceRepository.findByUserId(user2)).thenReturn(Optional.of(balance2));
            when(transactionRepository.findByUserId(user2)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 800L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));

            // When
            ReconciliationService.ReconciliationSummary summary = reconciliationService.reconcileAllUsers();

            // Then
            assertThat(summary).isNotNull();
            assertThat(summary.totalUsers()).isEqualTo(2);
            assertThat(summary.balancedUsers()).isEqualTo(2);
            assertThat(summary.usersWithDrift()).isEqualTo(0);
            assertThat(summary.totalDriftAmount()).isEqualTo(0);
            assertThat(summary.isSuccessful()).isTrue();
            assertThat(summary.driftResults()).isEmpty();
        }

        @Test
        @DisplayName("should identify users with drift")
        void reconcileAllUsers_someWithDrift_identifiesDrift() {
            // Given
            UUID user1 = UUID.randomUUID();
            UUID user2 = UUID.randomUUID();
            
            Balance balance1 = createBalance(user1, 500L, 0L); // Balanced
            Balance balance2 = createBalance(user2, 400L, 0L); // Has drift (should be 500)
            
            when(balanceRepository.findAll()).thenReturn(Arrays.asList(balance1, balance2));
            
            // User 1: 1000 purchase - 500 commit = 500 (balanced)
            when(balanceRepository.findByUserId(user1)).thenReturn(Optional.of(balance1));
            when(transactionRepository.findByUserId(user1)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 1000L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));
            
            // User 2: 1000 purchase - 500 commit = 500, but actual is 400 (drift = 100)
            when(balanceRepository.findByUserId(user2)).thenReturn(Optional.of(balance2));
            when(transactionRepository.findByUserId(user2)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 1000L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));

            // When
            ReconciliationService.ReconciliationSummary summary = reconciliationService.reconcileAllUsers();

            // Then
            assertThat(summary).isNotNull();
            assertThat(summary.totalUsers()).isEqualTo(2);
            assertThat(summary.balancedUsers()).isEqualTo(1);
            assertThat(summary.usersWithDrift()).isEqualTo(1);
            assertThat(summary.totalDriftAmount()).isEqualTo(100);
            assertThat(summary.isSuccessful()).isFalse();
            assertThat(summary.driftResults()).hasSize(1);
            assertThat(summary.driftResults().get(0).userId()).isEqualTo(user2);
        }

        @Test
        @DisplayName("should handle empty balances list")
        void reconcileAllUsers_emptyBalances_returnsEmptySummary() {
            // Given
            when(balanceRepository.findAll()).thenReturn(Collections.emptyList());

            // When
            ReconciliationService.ReconciliationSummary summary = reconciliationService.reconcileAllUsers();

            // Then
            assertThat(summary).isNotNull();
            assertThat(summary.totalUsers()).isEqualTo(0);
            assertThat(summary.balancedUsers()).isEqualTo(0);
            assertThat(summary.usersWithDrift()).isEqualTo(0);
            assertThat(summary.totalDriftAmount()).isEqualTo(0);
            assertThat(summary.isSuccessful()).isTrue();
        }

        @Test
        @DisplayName("should aggregate drift amounts from multiple users")
        void reconcileAllUsers_multipleDrifts_aggregatesAmounts() {
            // Given
            UUID user1 = UUID.randomUUID();
            UUID user2 = UUID.randomUUID();
            UUID user3 = UUID.randomUUID();
            
            Balance balance1 = createBalance(user1, 400L, 0L); // Drift: +100
            Balance balance2 = createBalance(user2, 250L, 0L); // Drift: +50
            Balance balance3 = createBalance(user3, 500L, 0L); // Balanced
            
            when(balanceRepository.findAll()).thenReturn(Arrays.asList(balance1, balance2, balance3));
            
            // User 1: calculated 500, actual 400, drift +100
            when(balanceRepository.findByUserId(user1)).thenReturn(Optional.of(balance1));
            when(transactionRepository.findByUserId(user1)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 1000L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));
            
            // User 2: calculated 300, actual 250, drift +50
            when(balanceRepository.findByUserId(user2)).thenReturn(Optional.of(balance2));
            when(transactionRepository.findByUserId(user2)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 800L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));
            
            // User 3: balanced
            when(balanceRepository.findByUserId(user3)).thenReturn(Optional.of(balance3));
            when(transactionRepository.findByUserId(user3)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 1000L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));

            // When
            ReconciliationService.ReconciliationSummary summary = reconciliationService.reconcileAllUsers();

            // Then
            assertThat(summary.totalUsers()).isEqualTo(3);
            assertThat(summary.balancedUsers()).isEqualTo(1);
            assertThat(summary.usersWithDrift()).isEqualTo(2);
            assertThat(summary.totalDriftAmount()).isEqualTo(150); // 100 + 50
            assertThat(summary.isSuccessful()).isFalse();
        }
    }

    @Nested
    @DisplayName("performWeeklyReconciliation Tests")
    class PerformWeeklyReconciliationTests {

        @Test
        @DisplayName("should complete successfully when all users balanced")
        void performWeeklyReconciliation_allBalanced_completesSuccessfully() {
            // Given
            UUID user1 = UUID.randomUUID();
            Balance balance1 = createBalance(user1, 500L, 0L);
            
            when(balanceRepository.findAll()).thenReturn(List.of(balance1));
            when(balanceRepository.findByUserId(user1)).thenReturn(Optional.of(balance1));
            when(transactionRepository.findByUserId(user1)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 1000L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));

            // When
            reconciliationService.performWeeklyReconciliation();

            // Then
            verify(balanceRepository).findAll();
        }

        @Test
        @DisplayName("should log warnings when drift detected")
        void performWeeklyReconciliation_driftDetected_logsWarnings() {
            // Given
            UUID user1 = UUID.randomUUID();
            Balance balance1 = createBalance(user1, 400L, 0L); // Has drift
            
            when(balanceRepository.findAll()).thenReturn(List.of(balance1));
            when(balanceRepository.findByUserId(user1)).thenReturn(Optional.of(balance1));
            when(transactionRepository.findByUserId(user1)).thenReturn(Arrays.asList(
                    createTransaction(TokenTransactionType.PURCHASE, 1000L),
                    createTransaction(TokenTransactionType.COMMIT, 500L)
            ));

            // When
            reconciliationService.performWeeklyReconciliation();

            // Then
            verify(balanceRepository).findAll();
            verify(metricsService).recordReconciliationDrift(eq(user1), anyLong());
        }

        @Test
        @DisplayName("should handle exceptions gracefully")
        void performWeeklyReconciliation_exceptionOccurs_handlesGracefully() {
            // Given
            when(balanceRepository.findAll()).thenThrow(new RuntimeException("Database error"));

            // When
            reconciliationService.performWeeklyReconciliation();

            // Then
            verify(balanceRepository).findAll();
            // Should not throw exception, just log it
        }
    }

    @Test
    @DisplayName("reconcileUser: when positive ADJUSTMENT transaction exists then adds to credits")
    void reconcileUser_positiveAdjustment_addsToCredits() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 1300 available, 0 reserved
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(1300L);
        balance.setReservedTokens(0L);
        
        // Create transactions: 1000 purchase, +300 adjustment (bonus)
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 1000L);
        TokenTransaction positiveAdjustment = createTransaction(TokenTransactionType.ADJUSTMENT, 300L);
        
        List<TokenTransaction> transactions = Arrays.asList(purchase, positiveAdjustment);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isBalanced()).isTrue();
        // Calculation: 1000 (purchase) + 300 (positive adjustment) = 1300
        assertThat(result.calculatedBalance()).isEqualTo(1300);
        assertThat(result.actualBalance()).isEqualTo(1300);
        
        verify(metricsService).recordReconciliationSuccess(userId);
    }

    @Test
    @DisplayName("reconcileUser: when mixed transaction types exist then calculates correctly")
    void reconcileUser_mixedTransactionTypes_calculatesCorrectly() {
        // Given
        UUID userId = UUID.randomUUID();
        
        // Create balance: 600 available, 100 reserved = 700 total
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(600L);
        balance.setReservedTokens(100L);
        
        // Create various transactions
        TokenTransaction purchase = createTransaction(TokenTransactionType.PURCHASE, 2000L);
        TokenTransaction commit = createTransaction(TokenTransactionType.COMMIT, 800L);
        TokenTransaction refund = createTransaction(TokenTransactionType.REFUND, -200L);
        TokenTransaction adjustment = createTransaction(TokenTransactionType.ADJUSTMENT, 100L);
        TokenTransaction reserve = createTransaction(TokenTransactionType.RESERVE, 100L);
        TokenTransaction release = createTransaction(TokenTransactionType.RELEASE, 50L);
        TokenTransaction negativeAdj = createTransaction(TokenTransactionType.ADJUSTMENT, -400L);
        
        List<TokenTransaction> transactions = Arrays.asList(
                purchase, commit, refund, adjustment, reserve, release, negativeAdj
        );
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByUserId(userId)).thenReturn(transactions);

        // When
        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileUser(userId);

        // Then
        assertThat(result).isNotNull();
        // Calculation:
        // Credits: 2000 (purchase) + 100 (positive adjustment) = 2100
        // Debits: 800 (commit) + 200 (refund) + 400 (negative adjustment) = 1400
        // Balance: 2100 - 1400 = 700
        assertThat(result.calculatedBalance()).isEqualTo(700);
        assertThat(result.actualBalance()).isEqualTo(700);
        assertThat(result.isBalanced()).isTrue();
    }

    // Helper methods
    private Balance createBalance(UUID userId, long available, long reserved) {
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(available);
        balance.setReservedTokens(reserved);
        return balance;
    }

    private TokenTransaction createTransaction(TokenTransactionType type, long amount) {
        TokenTransaction tx = new TokenTransaction();
        tx.setId(UUID.randomUUID());
        tx.setUserId(UUID.randomUUID());
        tx.setType(type);
        tx.setSource(TokenTransactionSource.QUIZ_GENERATION);
        tx.setAmountTokens(amount);
        tx.setRefId("test-ref");
        tx.setIdempotencyKey("test-key");
        tx.setBalanceAfterAvailable(0L);
        tx.setBalanceAfterReserved(0L);
        tx.setMetaJson("{}");
        tx.setCreatedAt(LocalDateTime.now());
        return tx;
    }
}
