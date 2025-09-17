package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.ReconciliationService;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationServiceImplTest {

    private BalanceRepository balanceRepository;
    private TokenTransactionRepository transactionRepository;
    private BillingMetricsService metricsService;
    private ReconciliationServiceImpl reconciliationService;

    @BeforeEach
    void setup() {
        balanceRepository = mock(BalanceRepository.class);
        transactionRepository = mock(TokenTransactionRepository.class);
        metricsService = mock(BillingMetricsService.class);
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
