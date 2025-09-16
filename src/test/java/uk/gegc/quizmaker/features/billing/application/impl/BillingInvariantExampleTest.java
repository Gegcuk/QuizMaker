package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.billing.domain.model.Reservation;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.billing.testutils.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Example test demonstrating how to use the new billing invariant assertion utilities.
 * 
 * This test shows the recommended patterns for:
 * - Using LedgerAsserts for invariant validation
 * - Using the JUnit extension for automatic checking
 * - Using BillingTestUtils for test setup
 * - Creating comprehensive test scenarios
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Billing Invariant Example Tests")
class BillingInvariantExampleTest {

    @Mock
    private BalanceRepository balanceRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private TokenTransactionRepository transactionRepository;
    @Mock
    private BillingService billingService;

    private TestLedgerView ledgerView;
    private UUID userId;
    private Balance initialBalance;

    // Note: JUnit extension would be registered here in a real test setup
    // @RegisterExtension
    // static LedgerInvariantExtension invariantExtension = LedgerInvariantExtension.builder(ledgerView)
    //     .enableI1Check(true)
    //     .enableI4Check(true)
    //     .build();

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        initialBalance = BillingTestUtils.createMockBalance(userId, 10000L, 0L);
        
        // Setup common mocks
        BillingTestUtils.setupCommonMocks(balanceRepository, reservationRepository, 
                                        transactionRepository, userId, 10000L, 0L);
        
        // Create ledger view (in real tests, this would be injected)
        ledgerView = new TestLedgerView(reservationRepository, transactionRepository, balanceRepository);
    }

    @Nested
    @DisplayName("Manual Invariant Validation Examples")
    class ManualInvariantValidationTests {

        @Test
        @DisplayName("Should validate I1 (Non-overspend) after reservation and commit")
        void shouldValidateI1_NonOverspendAfterReservationAndCommit() {
            // Given
            long estimatedTokens = 5000L;
            long actualTokens = 3000L;
            String idempotencyKey = "test-key-1";
            
            Reservation reservation = BillingTestUtils.createMockReservation(userId, estimatedTokens, ReservationState.ACTIVE);
            List<TokenTransaction> transactions = List.of(
                BillingTestUtils.createMockTransaction(reservation.getId(), TokenTransactionType.RESERVE, estimatedTokens, idempotencyKey),
                BillingTestUtils.createMockTransaction(reservation.getId(), TokenTransactionType.COMMIT, actualTokens, idempotencyKey + "-commit"),
                BillingTestUtils.createMockTransaction(reservation.getId(), TokenTransactionType.RELEASE, estimatedTokens - actualTokens, idempotencyKey + "-release")
            );

            // When - validate I1 (Non-overspend)
            LedgerAsserts.assertI1_NoOverspend(reservation, transactions);

            // Then - no exception should be thrown
            assertThat(transactions).hasSize(3);
        }

        @Test
        @DisplayName("Should validate I2 (Balance math) after operations")
        void shouldValidateI2_BalanceMathAfterOperations() {
            // Given
            AccountSnapshot before = AccountSnapshot.initial(userId, 10000L, 0L);
            AccountSnapshot after = AccountSnapshot.after(userId, 8000L, 0L, 0L, 0L, 2000L);

            // When - validate I2 (Balance math)
            LedgerAsserts.assertI2_BalanceMath(before, after);

            // Then - no exception should be thrown
            assertThat(after.available()).isEqualTo(8000L);
        }

        @Test
        @DisplayName("Should validate I3 (Cap rule) for commit operation")
        void shouldValidateI3_CapRuleForCommitOperation() {
            // Given
            long actualTokens = 3000L;
            long reservedTokens = 5000L;
            long committedTokens = 3000L; // min(actual, reserved)

            // When - validate I3 (Cap rule)
            LedgerAsserts.assertI3_CapRule(actualTokens, reservedTokens, committedTokens);

            // Then - no exception should be thrown
            assertThat(committedTokens).isEqualTo(Math.min(actualTokens, reservedTokens));
        }

        @Test
        @DisplayName("Should validate I4 (Idempotency) for duplicate operations")
        void shouldValidateI4_IdempotencyForDuplicateOperations() {
            // Given
            String idempotencyKey = "duplicate-key";
            List<TokenTransaction> transactions = List.of(
                BillingTestUtils.createMockTransaction(UUID.randomUUID(), TokenTransactionType.RESERVE, 1000L, idempotencyKey)
                // Only one transaction with this key and type
            );

            // When - validate I4 (Idempotency)
            LedgerAsserts.assertI4_Idempotent(idempotencyKey, TokenTransactionType.RESERVE, transactions);

            // Then - no exception should be thrown
            assertThat(transactions).hasSize(1);
        }

        @Test
        @DisplayName("Should validate I5 (State machine) for valid transitions")
        void shouldValidateI5_StateMachineForValidTransitions() {
            // Given
            BillingState from = BillingState.RESERVED;
            BillingState to = BillingState.COMMITTED;

            // When - validate I5 (State machine)
            LedgerAsserts.assertI5_StateMachine(from, to);

            // Then - no exception should be thrown
            assertThat(to).isEqualTo(BillingState.COMMITTED);
        }

        @Test
        @DisplayName("Should validate I6 (Rounding) for token conversions")
        void shouldValidateI6_RoundingForTokenConversions() {
            // Given
            long llmTokens = 1000L;
            long ratio = 3L;
            long billingTokens = 334L; // ceil(1000/3) = 334

            // When - validate I6 (Rounding)
            LedgerAsserts.assertI6_Rounding(llmTokens, ratio, billingTokens);

            // Then - no exception should be thrown
            assertThat(billingTokens).isEqualTo(334L);
        }
    }

    @Nested
    @DisplayName("Comprehensive Scenario Examples")
    class ComprehensiveScenarioTests {

        @Test
        @DisplayName("Should validate all invariants in complete billing flow")
        void shouldValidateAllInvariantsInCompleteBillingFlow() {
            // Given - create a complete test scenario
            BillingTestUtils.TestScenario scenario = BillingTestUtils.scenario()
                .userId(userId)
                .initialBalance(10000L, 0L)
                .addCredits(2000L)
                .reserveTokens(5000L)
                .commitTokens(3000L)
                .idempotencyKey("complete-flow-key")
                .build();

            // Create mock data
            Reservation reservation = BillingTestUtils.createMockReservation(userId, scenario.tokensToReserve(), ReservationState.COMMITTED);
            reservation.setCommittedTokens(scenario.actualTokensToCommit());
            
            List<TokenTransaction> transactions = List.of(
                BillingTestUtils.createMockTransaction(reservation.getId(), TokenTransactionType.PURCHASE, scenario.creditsToAdd(), scenario.idempotencyKey() + "-purchase"),
                BillingTestUtils.createMockTransaction(reservation.getId(), TokenTransactionType.RESERVE, scenario.tokensToReserve(), scenario.idempotencyKey() + "-reserve"),
                BillingTestUtils.createMockTransaction(reservation.getId(), TokenTransactionType.COMMIT, scenario.actualTokensToCommit(), scenario.idempotencyKey() + "-commit"),
                BillingTestUtils.createMockTransaction(reservation.getId(), TokenTransactionType.RELEASE, scenario.tokensToReserve() - scenario.actualTokensToCommit(), scenario.idempotencyKey() + "-release")
            );

            // When - validate all applicable invariants
            BillingTestUtils.validateAllInvariants(
                reservation, 
                transactions, 
                scenario.actualTokensToCommit(),
                scenario.getInitialSnapshot(),
                AccountSnapshot.after(userId, scenario.getExpectedFinalAvailable(), scenario.getExpectedFinalReserved(),
                                    scenario.creditsToAdd(), 0L, scenario.actualTokensToCommit())
            );

            // Then - no exceptions should be thrown
            assertThat(transactions).hasSize(4);
            assertThat(reservation.getCommittedTokens()).isEqualTo(scenario.actualTokensToCommit());
        }

        @Test
        @DisplayName("Should validate rounding in multiple conversion scenarios")
        void shouldValidateRoundingInMultipleConversionScenarios() {
            // Given - multiple conversion scenarios
            long[][] scenarios = {
                {1000L, 3L, 334L},    // ceil(1000/3) = 334
                {2000L, 5L, 400L},    // ceil(2000/5) = 400
                {1500L, 7L, 215L},    // ceil(1500/7) = 215
                {999L, 2L, 500L}      // ceil(999/2) = 500
            };

            // When & Then - validate each scenario
            for (long[] scenario : scenarios) {
                long llmTokens = scenario[0];
                long ratio = scenario[1];
                long expectedBillingTokens = scenario[2];
                
                BillingTestUtils.validateRounding(llmTokens, ratio, expectedBillingTokens);
            }
        }
    }

    @Nested
    @DisplayName("Utility Usage Examples")
    class UtilityUsageTests {

        @Test
        @DisplayName("Should demonstrate BillingTestUtils helper methods")
        void shouldDemonstrateBillingTestUtilsHelperMethods() {
            // Given
            UUID testUserId = UUID.randomUUID();
            long available = 5000L;
            long reserved = 1000L;

            // When - create mock objects using utilities
            Balance balance = BillingTestUtils.createMockBalance(testUserId, available, reserved);
            Reservation reservation = BillingTestUtils.createMockReservation(testUserId, 2000L, ReservationState.ACTIVE);
            TokenTransaction transaction = BillingTestUtils.createMockTransaction(reservation.getId(), TokenTransactionType.RESERVE, 2000L, "test-key");

            // Then - verify objects are created correctly
            assertThat(balance.getUserId()).isEqualTo(testUserId);
            assertThat(balance.getAvailableTokens()).isEqualTo(available);
            assertThat(balance.getReservedTokens()).isEqualTo(reserved);
            
            assertThat(reservation.getUserId()).isEqualTo(testUserId);
            assertThat(reservation.getEstimatedTokens()).isEqualTo(2000L);
            assertThat(reservation.getState()).isEqualTo(ReservationState.ACTIVE);
            
            assertThat(transaction.getRefId()).isEqualTo(reservation.getId().toString());
            assertThat(transaction.getType()).isEqualTo(TokenTransactionType.RESERVE);
            assertThat(transaction.getAmountTokens()).isEqualTo(2000L);
            assertThat(transaction.getIdempotencyKey()).isEqualTo("test-key");
        }
    }
}
