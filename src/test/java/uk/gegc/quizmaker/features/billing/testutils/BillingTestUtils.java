package uk.gegc.quizmaker.features.billing.testutils;

import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.billing.domain.model.Reservation;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * Utility class for setting up billing tests with proper mocking and data setup.
 * 
 * This class provides common test setup methods and demonstrates how to use
 * the new assertion utilities in billing tests.
 */
public final class BillingTestUtils {

    private BillingTestUtils() {
        // Utility class
    }

    /**
     * Creates a mock balance for testing.
     * 
     * @param userId The user ID
     * @param available Available tokens
     * @param reserved Reserved tokens
     * @return Mock balance
     */
    public static Balance createMockBalance(UUID userId, long available, long reserved) {
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(available);
        balance.setReservedTokens(reserved);
        balance.setVersion(1L);
        // Note: createdAt and updatedAt are handled by JPA annotations
        return balance;
    }

    /**
     * Creates a mock reservation for testing.
     * 
     * @param userId The user ID
     * @param estimatedTokens Estimated tokens
     * @param state The reservation state
     * @return Mock reservation
     */
    public static Reservation createMockReservation(UUID userId, long estimatedTokens, ReservationState state) {
        Reservation reservation = new Reservation();
        reservation.setId(UUID.randomUUID());
        reservation.setUserId(userId);
        reservation.setEstimatedTokens(estimatedTokens);
        reservation.setCommittedTokens(0L);
        reservation.setState(state);
        reservation.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        reservation.setCreatedAt(LocalDateTime.now());
        reservation.setUpdatedAt(LocalDateTime.now());
        return reservation;
    }

    /**
     * Creates a mock token transaction for testing.
     * 
     * @param reservationId The reservation ID
     * @param type The transaction type
     * @param amount The amount
     * @param idempotencyKey The idempotency key
     * @return Mock transaction
     */
    public static TokenTransaction createMockTransaction(UUID reservationId, TokenTransactionType type, 
                                                       long amount, String idempotencyKey) {
        TokenTransaction transaction = new TokenTransaction();
        transaction.setId(UUID.randomUUID());
        transaction.setRefId(reservationId.toString());
        transaction.setType(type);
        transaction.setAmountTokens(amount);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setCreatedAt(LocalDateTime.now());
        return transaction;
    }

    /**
     * Sets up common mocks for billing service tests.
     * 
     * @param balanceRepository The balance repository mock
     * @param reservationRepository The reservation repository mock
     * @param transactionRepository The transaction repository mock
     * @param userId The user ID
     * @param initialAvailable Initial available tokens
     * @param initialReserved Initial reserved tokens
     */
    public static void setupCommonMocks(BalanceRepository balanceRepository,
                                      ReservationRepository reservationRepository,
                                      TokenTransactionRepository transactionRepository,
                                      UUID userId, long initialAvailable, long initialReserved) {
        
        Balance balance = createMockBalance(userId, initialAvailable, initialReserved);
        
        lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        lenient().when(balanceRepository.save(any(Balance.class))).thenReturn(balance);
        lenient().when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            if (reservation.getId() == null) {
                reservation.setId(UUID.randomUUID());
            }
            return reservation;
        });
        lenient().when(transactionRepository.save(any(TokenTransaction.class))).thenAnswer(invocation -> {
            TokenTransaction transaction = invocation.getArgument(0);
            if (transaction.getId() == null) {
                transaction.setId(UUID.randomUUID());
            }
            return transaction;
        });
    }

    /**
     * Creates an account snapshot for balance math validation.
     * 
     * @param balance The current balance
     * @param totalCreditsAdded Total credits added during operations
     * @param totalAdjustments Total adjustments made
     * @param totalCommitted Total tokens committed
     * @return Account snapshot
     */
    public static AccountSnapshot createAccountSnapshot(Balance balance, long totalCreditsAdded, 
                                                       long totalAdjustments, long totalCommitted) {
        return AccountSnapshot.after(
            balance.getUserId(),
            balance.getAvailableTokens(),
            balance.getReservedTokens(),
            totalCreditsAdded,
            totalAdjustments,
            totalCommitted
        );
    }

    /**
     * Validates all applicable invariants for a reservation operation.
     * 
     * @param reservation The reservation
     * @param transactions The related transactions
     * @param actualTokens The actual tokens used (for I3)
     * @param beforeSnapshot The account state before (for I2)
     * @param afterSnapshot The account state after (for I2)
     */
    public static void validateAllInvariants(Reservation reservation, List<TokenTransaction> transactions,
                                           Long actualTokens, AccountSnapshot beforeSnapshot, 
                                           AccountSnapshot afterSnapshot) {
        
        // I1: Non-overspend (always applicable for reservations)
        LedgerAsserts.assertI1_NoOverspend(reservation, transactions);
        
        // I2: Balance math (if snapshots provided)
        if (beforeSnapshot != null && afterSnapshot != null) {
            LedgerAsserts.assertI2_BalanceMath(beforeSnapshot, afterSnapshot);
        }
        
        // I3: Cap rule (if actual tokens provided)
        if (actualTokens != null) {
            LedgerAsserts.assertI3_CapRule(actualTokens, reservation.getEstimatedTokens(), 
                                         reservation.getCommittedTokens());
        }
        
        // I4: Idempotency (check for duplicate transactions)
        transactions.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                tx -> tx.getIdempotencyKey() + ":" + tx.getType(),
                java.util.stream.Collectors.toList()
            ))
            .forEach((key, txList) -> {
                String[] parts = key.split(":");
                String idempotencyKey = parts[0];
                TokenTransactionType operationType = TokenTransactionType.valueOf(parts[1]);
                LedgerAsserts.assertI4_Idempotent(idempotencyKey, operationType, txList);
            });
        
        // I5: State machine (if state transitions occurred)
        // This would need to be tracked separately in a real implementation
    }

    /**
     * Validates rounding for token conversions.
     * 
     * @param llmTokens The LLM tokens
     * @param ratio The conversion ratio
     * @param billingTokens The resulting billing tokens
     */
    public static void validateRounding(long llmTokens, long ratio, long billingTokens) {
        LedgerAsserts.assertI6_Rounding(llmTokens, ratio, billingTokens);
    }

    /**
     * Validates rounding for token conversions with double ratio.
     * 
     * @param llmTokens The LLM tokens
     * @param ratio The conversion ratio
     * @param billingTokens The resulting billing tokens
     */
    public static void validateRounding(long llmTokens, double ratio, long billingTokens) {
        LedgerAsserts.assertI6_Rounding(llmTokens, ratio, billingTokens, 0.001);
    }

    /**
     * Creates a test scenario builder for complex test setups.
     * 
     * @return A new test scenario builder
     */
    public static TestScenarioBuilder scenario() {
        return new TestScenarioBuilder();
    }

    /**
     * Builder class for creating complex test scenarios.
     */
    public static class TestScenarioBuilder {
        private UUID userId;
        private long initialAvailable;
        private long initialReserved;
        private long creditsToAdd;
        private long tokensToReserve;
        private long actualTokensToCommit;
        private String idempotencyKey;

        public TestScenarioBuilder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public TestScenarioBuilder initialBalance(long available, long reserved) {
            this.initialAvailable = available;
            this.initialReserved = reserved;
            return this;
        }

        public TestScenarioBuilder addCredits(long credits) {
            this.creditsToAdd = credits;
            return this;
        }

        public TestScenarioBuilder reserveTokens(long tokens) {
            this.tokensToReserve = tokens;
            return this;
        }

        public TestScenarioBuilder commitTokens(long tokens) {
            this.actualTokensToCommit = tokens;
            return this;
        }

        public TestScenarioBuilder idempotencyKey(String key) {
            this.idempotencyKey = key;
            return this;
        }

        public TestScenario build() {
            return new TestScenario(userId, initialAvailable, initialReserved, 
                                  creditsToAdd, tokensToReserve, actualTokensToCommit, idempotencyKey);
        }
    }

    /**
     * Record representing a complete test scenario.
     */
    public record TestScenario(
        UUID userId,
        long initialAvailable,
        long initialReserved,
        long creditsToAdd,
        long tokensToReserve,
        long actualTokensToCommit,
        String idempotencyKey
    ) {
        public AccountSnapshot getInitialSnapshot() {
            return AccountSnapshot.initial(userId, initialAvailable, initialReserved);
        }

        public long getExpectedFinalAvailable() {
            return initialAvailable + creditsToAdd - tokensToReserve + (tokensToReserve - actualTokensToCommit);
        }

        public long getExpectedFinalReserved() {
            return 0L; // Reservation is fully consumed after commit
        }
    }
}
