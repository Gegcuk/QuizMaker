package uk.gegc.quizmaker.features.billing.application;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

/**
 * Stripe configuration properties: keys, URLs, and price IDs.
 * Validates that required properties are set when billing feature is enabled.
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "stripe")
@Data
@RequiredArgsConstructor
public class StripeProperties {
    
    private final FeatureFlags featureFlags;
    
    /** Secret API key (server-side). */
    private String secretKey;

    /** Publishable API key (client-side). */
    private String publishableKey;

    /** Webhook signing secret for signature verification. */
    private String webhookSecret;

    /** Success redirect URL for Checkout Sessions. */
    private String successUrl;

    /** Cancel redirect URL for Checkout Sessions. */
    private String cancelUrl;

    /** Price IDs (optional, if not using DB-managed packs). */
    private String priceSmall;
    private String priceMedium;
    private String priceLarge;
    
    /**
     * Validates that required Stripe properties are configured when billing is enabled.
     * This ensures we fail fast at startup rather than silently at runtime.
     */
    @PostConstruct
    void validateConfiguration() {
        if (!featureFlags.isBilling()) {
            log.debug("Billing feature is disabled, skipping Stripe configuration validation");
            return;
        }
        
        if (!StringUtils.hasText(webhookSecret)) {
            throw new IllegalStateException(
                "Stripe webhook secret is required when billing feature is enabled. " +
                "Please set STRIPE_WEBHOOK_SECRET environment variable or stripe.webhook-secret property. " +
                "If billing is not needed, set quizmaker.features.billing=false"
            );
        }
        
        log.info("Stripe configuration validated successfully for billing feature");
    }
}

