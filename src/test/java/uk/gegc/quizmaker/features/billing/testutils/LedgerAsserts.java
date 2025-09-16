package uk.gegc.quizmaker.features.billing.testutils;

import org.assertj.core.api.Assertions;
import uk.gegc.quizmaker.features.billing.domain.model.Reservation;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;

import java.util.List;
import java.util.UUID;

/**
 * Utility class for asserting core billing invariants across tests.
 * 
 * These assertions ensure the integrity of the billing system by validating
 * the six core invariants (I1-I6) that must hold at all times.
 */
public final class LedgerAsserts {

    private LedgerAsserts() {
        // Utility class
    }

    /**
     * I1. Non-overspend: For any reservation R, Σ(committed for R) ≤ R and 
     * Σ(released for R) + Σ(committed for R) = R.
     * 
     * @param reservation The reservation to validate
     * @param transactions All transactions related to this reservation
     */
    public static void assertI1_NoOverspend(Reservation reservation, List<TokenTransaction> transactions) {
        long reserved = reservation.getEstimatedTokens();
        long committed = transactions.stream()
            .filter(tx -> tx.getType() == TokenTransactionType.COMMIT)
            .mapToLong(TokenTransaction::getAmountTokens)
            .sum();
        long released = transactions.stream()
            .filter(tx -> tx.getType() == TokenTransactionType.RELEASE)
            .mapToLong(TokenTransaction::getAmountTokens)
            .sum();

        Assertions.assertThat(committed)
            .as("Σ(committed) ≤ reserved for reservation %s", reservation.getId())
            .isLessThanOrEqualTo(reserved);
        
        Assertions.assertThat(committed + released)
            .as("Σ(released)+Σ(committed) == reserved for reservation %s", reservation.getId())
            .isEqualTo(reserved);
    }

    /**
     * I1. Non-overspend: For any reservation R, Σ(committed for R) ≤ R and 
     * Σ(released for R) + Σ(committed for R) = R.
     * 
     * @param reservationId The reservation ID to validate
     * @param view The ledger view for querying data
     */
    public static void assertI1_NoOverspend(UUID reservationId, LedgerView view) {
        long reserved = view.sumReserved(reservationId);
        long committed = view.sumCommitted(reservationId);
        long released = view.sumReleased(reservationId);

        Assertions.assertThat(committed)
            .as("Σ(committed) ≤ reserved for reservation %s", reservationId)
            .isLessThanOrEqualTo(reserved);
        
        Assertions.assertThat(committed + released)
            .as("Σ(released)+Σ(committed) == reserved for reservation %s", reservationId)
            .isEqualTo(reserved);
    }

    /**
     * I2. Balance math: final_available = initial + credits + adjustments − Σ(all committed)
     * (reservations don't change available).
     * 
     * @param before The account state before operations
     * @param after The account state after operations
     */
    public static void assertI2_BalanceMath(AccountSnapshot before, AccountSnapshot after) {
        long expected = before.available()
            + after.totalCreditsAdded()
            + after.totalAdjustments()
            - after.totalCommitted();

        Assertions.assertThat(after.available())
            .as("final_available math: initial=%d + credits=%d + adjustments=%d - committed=%d = %d",
                before.available(), after.totalCreditsAdded(), after.totalAdjustments(), 
                after.totalCommitted(), expected)
            .isEqualTo(expected);
    }

    /**
     * I2. Balance math: final_available = initial + credits + adjustments − Σ(all committed)
     * 
     * @param initialAvailable Initial available tokens
     * @param creditsAdded Total credits added during operations
     * @param adjustments Total adjustments made
     * @param totalCommitted Total tokens committed across all reservations
     * @param finalAvailable Final available tokens
     */
    public static void assertI2_BalanceMath(long initialAvailable, long creditsAdded, 
                                          long adjustments, long totalCommitted, long finalAvailable) {
        long expected = initialAvailable + creditsAdded + adjustments - totalCommitted;

        Assertions.assertThat(finalAvailable)
            .as("final_available math: initial=%d + credits=%d + adjustments=%d - committed=%d = %d",
                initialAvailable, creditsAdded, adjustments, totalCommitted, expected)
            .isEqualTo(expected);
    }

    /**
     * I3. Cap rule: committed = min(actual, reserved) (no epsilon), 
     * remainder = reserved − committed ≥ 0.
     * 
     * @param actual The actual tokens used
     * @param reserved The reserved tokens
     * @param committed The committed tokens
     */
    public static void assertI3_CapRule(long actual, long reserved, long committed) {
        long expected = Math.min(actual, reserved);
        
        Assertions.assertThat(committed)
            .as("committed = min(actual=%d, reserved=%d) = %d", actual, reserved, expected)
            .isEqualTo(expected);
        
        Assertions.assertThat(reserved - committed)
            .as("remainder = reserved - committed = %d - %d = %d", reserved, committed, reserved - committed)
            .isGreaterThanOrEqualTo(0);
    }

    /**
     * I4. Idempotency: Same idempotency key ⇒ at-most-once effect per operation type.
     * 
     * @param beforeCount Count of transactions before operation
     * @param afterCount Count of transactions after operation
     */
    public static void assertI4_Idempotent(long beforeCount, long afterCount) {
        Assertions.assertThat(afterCount - beforeCount)
            .as("idempotency ⇒ at-most-once ledger effect: before=%d, after=%d, diff=%d",
                beforeCount, afterCount, afterCount - beforeCount)
            .isLessThanOrEqualTo(1);
    }

    /**
     * I4. Idempotency: Same idempotency key ⇒ at-most-once effect per operation type.
     * 
     * @param idempotencyKey The idempotency key to check
     * @param operationType The operation type
     * @param transactions All transactions with this key and type
     */
    public static void assertI4_Idempotent(String idempotencyKey, TokenTransactionType operationType, 
                                         List<TokenTransaction> transactions) {
        long count = transactions.stream()
            .filter(tx -> tx.getIdempotencyKey().equals(idempotencyKey) && tx.getType() == operationType)
            .count();

        Assertions.assertThat(count)
            .as("idempotency ⇒ at-most-once effect for key=%s, type=%s", idempotencyKey, operationType)
            .isLessThanOrEqualTo(1);
    }

    /**
     * I5. State machine: NONE → RESERVED → (COMMITTED | RELEASED | EXPIRED). No illegal skips.
     * 
     * @param prev Previous state
     * @param next Next state
     */
    public static void assertI5_StateMachine(ReservationState prev, ReservationState next) {
        boolean ok = isValidStateTransition(prev, next);
        
        Assertions.assertThat(ok)
            .as("legal state transition %s → %s", prev, next)
            .isTrue();
    }

    /**
     * I5. State machine: NONE → RESERVED → (COMMITTED | RELEASED | EXPIRED). No illegal skips.
     * 
     * @param prev Previous state
     * @param next Next state
     */
    public static void assertI5_StateMachine(BillingState prev, BillingState next) {
        boolean ok = isValidStateTransition(prev, next);
        
        Assertions.assertThat(ok)
            .as("legal state transition %s → %s", prev, next)
            .isTrue();
    }

    /**
     * I6. Rounding: Conversions use ceil where specified; no fractional billing tokens.
     * 
     * @param llmTokens The LLM tokens to convert
     * @param ratio The conversion ratio
     * @param billingTokens The resulting billing tokens
     */
    public static void assertI6_Rounding(long llmTokens, long ratio, long billingTokens) {
        long expected = (long) Math.ceil(llmTokens / (double) ratio);
        
        Assertions.assertThat(billingTokens)
            .as("rounding: ceil(%d / %d) = %d", llmTokens, ratio, expected)
            .isEqualTo(expected);
    }

    /**
     * I6. Rounding: Conversions use ceil where specified; no fractional billing tokens.
     * 
     * @param llmTokens The LLM tokens to convert
     * @param ratio The conversion ratio
     * @param billingTokens The resulting billing tokens
     * @param tolerance Allowed tolerance for floating point precision
     */
    public static void assertI6_Rounding(long llmTokens, double ratio, long billingTokens, double tolerance) {
        long expected = (long) Math.ceil(llmTokens / ratio);
        
        Assertions.assertThat(billingTokens)
            .as("rounding: ceil(%d / %.2f) = %d", llmTokens, ratio, expected)
            .isEqualTo(expected);
    }

    /**
     * Validates that a state transition is legal according to the state machine rules.
     * 
     * @param prev Previous state
     * @param next Next state
     * @return true if the transition is valid
     */
    private static boolean isValidStateTransition(ReservationState prev, ReservationState next) {
        if (prev == null) {
            return next == ReservationState.ACTIVE; // NONE → RESERVED
        }
        
        switch (prev) {
            case ACTIVE:
                return next == ReservationState.COMMITTED || 
                       next == ReservationState.RELEASED || 
                       next == ReservationState.CANCELLED ||
                       next == ReservationState.EXPIRED;
            case COMMITTED:
            case RELEASED:
            case CANCELLED:
            case EXPIRED:
                return next == prev; // Idempotent re-reads
            default:
                return false;
        }
    }

    /**
     * Validates that a state transition is legal according to the state machine rules.
     * 
     * @param prev Previous state
     * @param next Next state
     * @return true if the transition is valid
     */
    private static boolean isValidStateTransition(BillingState prev, BillingState next) {
        if (prev == null) {
            return next == BillingState.RESERVED; // NONE → RESERVED
        }
        
        switch (prev) {
            case NONE:
                return next == BillingState.RESERVED;
            case RESERVED:
                return next == BillingState.COMMITTED || 
                       next == BillingState.RELEASED || 
                       next == BillingState.EXPIRED;
            case COMMITTED:
            case RELEASED:
            case EXPIRED:
                return next == prev; // Idempotent re-reads
            default:
                return false;
        }
    }
}
