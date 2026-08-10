package uk.gegc.quizmaker.features.ai.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Provider-reported usage for one actual remote attempt. A null token value
 * means the provider returned a response without usage metadata.
 */
public record ProviderUsageObservation(UUID providerAttemptId, Long providerLlmTokens) {

    public ProviderUsageObservation {
        Objects.requireNonNull(providerAttemptId, "providerAttemptId must not be null");
        if (providerLlmTokens != null && providerLlmTokens < 0L) {
            throw new IllegalArgumentException("providerLlmTokens must not be negative");
        }
    }

    public boolean isReported() {
        return providerLlmTokens != null;
    }
}
