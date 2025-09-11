package uk.gegc.quizmaker.features.billing.application;

import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Structured logging utility for billing operations.
 * Provides consistent structured logging with key fields for observability.
 */
public class BillingStructuredLogger {

    /**
     * Log a ledger write operation with structured fields.
     */
    public static void logLedgerWrite(Logger logger, String level, String message, 
            UUID userId, String txType, String source, long amount, 
            String idempotencyKey, long balanceAfterAvailable, long balanceAfterReserved, 
            String refId, Object... additionalArgs) {
        
        // Set MDC context for structured logging
        MDC.put("billing.userId", userId != null ? userId.toString() : null);
        MDC.put("billing.txType", txType);
        MDC.put("billing.source", source);
        MDC.put("billing.amount", String.valueOf(amount));
        MDC.put("billing.idempotencyKey", idempotencyKey);
        MDC.put("billing.balanceAfterAvailable", String.valueOf(balanceAfterAvailable));
        MDC.put("billing.balanceAfterReserved", String.valueOf(balanceAfterReserved));
        MDC.put("billing.refId", refId);
        
        try {
            switch (level.toLowerCase()) {
                case "info" -> logger.info(message, additionalArgs);
                case "warn" -> logger.warn(message, additionalArgs);
                case "error" -> logger.error(message, additionalArgs);
                case "debug" -> logger.debug(message, additionalArgs);
                default -> logger.info(message, additionalArgs);
            }
        } finally {
            // Clear MDC context
            clearBillingMDC();
        }
    }
    
    /**
     * Log a reservation operation.
     */
    public static void logReservationOperation(Logger logger, String level, String message,
            UUID userId, String operation, long amount, String reservationId, 
            long balanceAfterAvailable, long balanceAfterReserved, Object... additionalArgs) {
        
        MDC.put("billing.userId", userId != null ? userId.toString() : null);
        MDC.put("billing.operation", operation);
        MDC.put("billing.amount", String.valueOf(amount));
        MDC.put("billing.reservationId", reservationId);
        MDC.put("billing.balanceAfterAvailable", String.valueOf(balanceAfterAvailable));
        MDC.put("billing.balanceAfterReserved", String.valueOf(balanceAfterReserved));
        
        try {
            switch (level.toLowerCase()) {
                case "info" -> logger.info(message, additionalArgs);
                case "warn" -> logger.warn(message, additionalArgs);
                case "error" -> logger.error(message, additionalArgs);
                case "debug" -> logger.debug(message, additionalArgs);
                default -> logger.info(message, additionalArgs);
            }
        } finally {
            clearBillingMDC();
        }
    }
    
    /**
     * Log a webhook operation.
     */
    public static void logWebhookOperation(Logger logger, String level, String message,
            String eventId, String eventType, String result, String sessionId, 
            UUID userId, Object... additionalArgs) {
        
        MDC.put("billing.webhook.eventId", eventId);
        MDC.put("billing.webhook.eventType", eventType);
        MDC.put("billing.webhook.result", result);
        MDC.put("billing.webhook.sessionId", sessionId);
        MDC.put("billing.userId", userId != null ? userId.toString() : null);
        
        try {
            switch (level.toLowerCase()) {
                case "info" -> logger.info(message, additionalArgs);
                case "warn" -> logger.warn(message, additionalArgs);
                case "error" -> logger.error(message, additionalArgs);
                case "debug" -> logger.debug(message, additionalArgs);
                default -> logger.info(message, additionalArgs);
            }
        } finally {
            clearBillingMDC();
        }
    }
    
    /**
     * Clear billing-specific MDC context.
     */
    public static void clearBillingMDC() {
        MDC.remove("billing.userId");
        MDC.remove("billing.txType");
        MDC.remove("billing.source");
        MDC.remove("billing.amount");
        MDC.remove("billing.idempotencyKey");
        MDC.remove("billing.balanceAfterAvailable");
        MDC.remove("billing.balanceAfterReserved");
        MDC.remove("billing.refId");
        MDC.remove("billing.operation");
        MDC.remove("billing.reservationId");
        MDC.remove("billing.webhook.eventId");
        MDC.remove("billing.webhook.eventType");
        MDC.remove("billing.webhook.result");
        MDC.remove("billing.webhook.sessionId");
    }
}
