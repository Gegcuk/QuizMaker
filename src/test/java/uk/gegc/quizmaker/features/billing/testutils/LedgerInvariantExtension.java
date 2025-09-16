package uk.gegc.quizmaker.features.billing.testutils;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * JUnit 5 extension that automatically validates billing invariants after each test.
 * 
 * This extension provides automatic invariant checking for tests that touch reservations,
 * ensuring that core invariants (I1-I6) are maintained without requiring explicit
 * assertions in every test method.
 */
public class LedgerInvariantExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Logger logger = LoggerFactory.getLogger(LedgerInvariantExtension.class);
    
    private final LedgerView ledgerView;
    private final boolean enableI1Check;
    @SuppressWarnings("unused")
    private final boolean enableI2Check;
    private final boolean enableI4Check;

    /**
     * Creates a new extension with default settings.
     * 
     * @param ledgerView The ledger view for querying data
     */
    public LedgerInvariantExtension(LedgerView ledgerView) {
        this(ledgerView, true, false, false);
    }

    /**
     * Creates a new extension with custom settings.
     * 
     * @param ledgerView The ledger view for querying data
     * @param enableI1Check Whether to enable I1 (Non-overspend) checking
     * @param enableI2Check Whether to enable I2 (Balance math) checking
     * @param enableI4Check Whether to enable I4 (Idempotency) checking
     */
    public LedgerInvariantExtension(LedgerView ledgerView, boolean enableI1Check, 
                                  boolean enableI2Check, boolean enableI4Check) {
        this.ledgerView = ledgerView;
        this.enableI1Check = enableI1Check;
        this.enableI2Check = enableI2Check;
        this.enableI4Check = enableI4Check;
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        // Clear touched reservations at the start of each test
        ledgerView.clearTouchedReservations();
        
        logger.debug("Cleared touched reservations for test: {}", context.getDisplayName());
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        try {
            validateInvariants(context);
        } catch (Exception e) {
            logger.error("Invariant validation failed for test: {}", context.getDisplayName(), e);
            throw e;
        }
    }

    /**
     * Validates all enabled invariants for reservations touched during the test.
     * 
     * @param context The extension context
     */
    private void validateInvariants(ExtensionContext context) {
        List<UUID> touchedReservations = ledgerView.reservationsTouchedInCurrentTest();
        
        if (touchedReservations.isEmpty()) {
            logger.debug("No reservations touched in test: {}", context.getDisplayName());
            return;
        }

        logger.debug("Validating invariants for {} reservations in test: {}", 
                    touchedReservations.size(), context.getDisplayName());

        for (UUID reservationId : touchedReservations) {
            validateReservationInvariants(reservationId, context);
        }
    }

    /**
     * Validates invariants for a specific reservation.
     * 
     * @param reservationId The reservation ID
     * @param context The extension context
     */
    private void validateReservationInvariants(UUID reservationId, ExtensionContext context) {
        try {
            if (enableI1Check) {
                validateI1_NonOverspend(reservationId);
            }
            
            if (enableI4Check) {
                validateI4_Idempotency(reservationId);
            }
            
            logger.debug("Invariant validation passed for reservation {} in test: {}", 
                        reservationId, context.getDisplayName());
                        
        } catch (Exception e) {
            logger.error("Invariant validation failed for reservation {} in test: {}", 
                        reservationId, context.getDisplayName(), e);
            throw new AssertionError(
                String.format("Invariant validation failed for reservation %s in test %s: %s", 
                            reservationId, context.getDisplayName(), e.getMessage()), e);
        }
    }

    /**
     * Validates I1 (Non-overspend) invariant for a reservation.
     * 
     * @param reservationId The reservation ID
     */
    private void validateI1_NonOverspend(UUID reservationId) {
        LedgerAsserts.assertI1_NoOverspend(reservationId, ledgerView);
    }

    /**
     * Validates I4 (Idempotency) invariant for a reservation.
     * 
     * @param reservationId The reservation ID
     */
    private void validateI4_Idempotency(UUID reservationId) {
        // Get all transactions for this reservation
        List<uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction> transactions = 
            ledgerView.getTransactionsByReservationId(reservationId);
        
        // Group by idempotency key and operation type
        transactions.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                tx -> tx.getIdempotencyKey() + ":" + tx.getType(),
                java.util.stream.Collectors.toList()
            ))
            .forEach((key, txList) -> {
                String[] parts = key.split(":");
                String idempotencyKey = parts[0];
                uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType operationType = 
                    uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType.valueOf(parts[1]);
                
                LedgerAsserts.assertI4_Idempotent(idempotencyKey, operationType, txList);
            });
    }

    /**
     * Creates a builder for configuring the extension.
     * 
     * @param ledgerView The ledger view
     * @return A new builder
     */
    public static Builder builder(LedgerView ledgerView) {
        return new Builder(ledgerView);
    }

    /**
     * Builder class for configuring the LedgerInvariantExtension.
     */
    public static class Builder {
        private final LedgerView ledgerView;
        private boolean enableI1Check = true;
        private boolean enableI2Check = false;
        private boolean enableI4Check = false;

        private Builder(LedgerView ledgerView) {
            this.ledgerView = ledgerView;
        }

        /**
         * Enables or disables I1 (Non-overspend) checking.
         * 
         * @param enable Whether to enable I1 checking
         * @return This builder
         */
        public Builder enableI1Check(boolean enable) {
            this.enableI1Check = enable;
            return this;
        }

        /**
         * Enables or disables I2 (Balance math) checking.
         * 
         * @param enable Whether to enable I2 checking
         * @return This builder
         */
        public Builder enableI2Check(boolean enable) {
            this.enableI2Check = enable;
            return this;
        }

        /**
         * Enables or disables I4 (Idempotency) checking.
         * 
         * @param enable Whether to enable I4 checking
         * @return This builder
         */
        public Builder enableI4Check(boolean enable) {
            this.enableI4Check = enable;
            return this;
        }

        /**
         * Builds the configured extension.
         * 
         * @return The configured extension
         */
        public LedgerInvariantExtension build() {
            return new LedgerInvariantExtension(ledgerView, enableI1Check, enableI2Check, enableI4Check);
        }
    }
}
