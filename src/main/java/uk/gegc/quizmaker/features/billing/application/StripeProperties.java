package uk.gegc.quizmaker.features.billing.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Stripe configuration properties: keys, URLs, and price IDs.
 */
@Configuration
@ConfigurationProperties(prefix = "stripe")
@Data
public class StripeProperties {
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
}

