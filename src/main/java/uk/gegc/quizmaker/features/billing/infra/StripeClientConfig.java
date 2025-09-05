package uk.gegc.quizmaker.features.billing.infra;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;

/**
 * Stripe client configuration.
 * Initializes the global API key and exposes a typed client for integrations.
 */
@Configuration
@RequiredArgsConstructor
public class StripeClientConfig {

    private final StripeProperties stripe;

    @PostConstruct
    void init() {
        if (StringUtils.hasText(stripe.getSecretKey())) {
            Stripe.apiKey = stripe.getSecretKey();
        }
    }

    /**
     * Provide a reusable StripeClient only when the secret key is configured.
     */
    @Bean
    @ConditionalOnProperty(name = "stripe.secret-key")
    public StripeClient stripeClient() {
        return new StripeClient(stripe.getSecretKey());
    }
}
