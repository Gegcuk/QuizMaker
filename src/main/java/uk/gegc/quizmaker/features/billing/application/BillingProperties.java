package uk.gegc.quizmaker.features.billing.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Billing configuration (token accounting parameters).
 */
@Configuration
@ConfigurationProperties(prefix = "billing")
@Validated
@Data
public class BillingProperties {
    /**
     * Conversion ratio: 1 billing token = X LLM tokens (ceil rounding).
     */
    @Positive
    private long tokenToLlmRatio = 1000L;

    /**
     * Safety factor to apply to estimates (>= 1.0; e.g., 1.2).
     */
    @DecimalMin("1.0")
    private double safetyFactor = 1.2d;

    /**
     * Reservation TTL in minutes before auto-expire and release.
     */
    @Positive
    private int reservationTtlMinutes = 120;

    /**
     * Single MVP currency code (e.g., usd).
     */
    private String currency = "usd";

    /**
     * Whether to enforce strict amount validation in checkout sessions.
     * When true, amount mismatches will throw exceptions.
     * When false, amount mismatches will only log warnings.
     */
    private boolean strictAmountValidation = true;

    /**
     * Whether to allow negative token balances.
     * When true, users can have negative balances (e.g., from refunds).
     * When false, balances are clamped to zero.
     */
    private boolean allowNegativeBalance = true;
}
