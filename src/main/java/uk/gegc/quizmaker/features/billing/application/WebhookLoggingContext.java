package uk.gegc.quizmaker.features.billing.application;

import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Structured logging context for webhook processing.
 * Provides consistent logging fields across all webhook handlers.
 */
@Data
@Builder
public class WebhookLoggingContext {
    private String eventId;
    private String eventType;
    private String sessionId;
    private UUID userId;
    private String priceId;
    private String subscriptionId;
    private String chargeId;
    private String disputeId;
    private String customerId;
    
    /**
     * Set MDC context for structured logging.
     */
    public void setMDC() {
        if (eventId != null) MDC.put("stripe_event_id", eventId);
        if (eventType != null) MDC.put("stripe_event_type", eventType);
        if (sessionId != null) MDC.put("stripe_session_id", sessionId);
        if (userId != null) MDC.put("user_id", userId.toString());
        if (priceId != null) MDC.put("stripe_price_id", priceId);
        if (subscriptionId != null) MDC.put("stripe_subscription_id", subscriptionId);
        if (chargeId != null) MDC.put("stripe_charge_id", chargeId);
        if (disputeId != null) MDC.put("stripe_dispute_id", disputeId);
        if (customerId != null) MDC.put("stripe_customer_id", customerId);
    }
    
    /**
     * Clear MDC context.
     */
    public static void clearMDC() {
        MDC.remove("stripe_event_id");
        MDC.remove("stripe_event_type");
        MDC.remove("stripe_session_id");
        MDC.remove("user_id");
        MDC.remove("stripe_price_id");
        MDC.remove("stripe_subscription_id");
        MDC.remove("stripe_charge_id");
        MDC.remove("stripe_dispute_id");
        MDC.remove("stripe_customer_id");
    }
    
    /**
     * Log with structured context.
     */
    public void logInfo(Logger logger, String message, Object... args) {
        setMDC();
        try {
            logger.info(message, args);
        } finally {
            clearMDC();
        }
    }
    
    public void logWarn(Logger logger, String message, Object... args) {
        setMDC();
        try {
            logger.warn(message, args);
        } finally {
            clearMDC();
        }
    }
    
    public void logError(Logger logger, String message, Throwable throwable) {
        setMDC();
        try {
            logger.error(message, throwable);
        } finally {
            clearMDC();
        }
    }
    
    public void logError(Logger logger, String message, Object... args) {
        setMDC();
        try {
            logger.error(message, args);
        } finally {
            clearMDC();
        }
    }
}
