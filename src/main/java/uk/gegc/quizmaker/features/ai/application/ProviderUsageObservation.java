package uk.gegc.quizmaker.features.ai.application;

import java.util.Objects;
import java.util.UUID;

/**
 * One lifecycle fact for an actual provider attempt. No prompt or response
 * content crosses this internal accounting boundary.
 */
public record ProviderUsageObservation(
        UUID providerAttemptId,
        State state,
        Long providerLlmTokens
) {

    public enum State {
        STARTED,
        REPORTED,
        MISSING,
        FAILED
    }

    public ProviderUsageObservation {
        Objects.requireNonNull(providerAttemptId, "providerAttemptId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (state == State.REPORTED) {
            if (providerLlmTokens == null || providerLlmTokens < 0L) {
                throw new IllegalArgumentException("reported providerLlmTokens must not be negative");
            }
        } else if (providerLlmTokens != null) {
            throw new IllegalArgumentException("non-reported provider event must not contain tokens");
        }
    }

    public static ProviderUsageObservation started(UUID providerAttemptId) {
        return new ProviderUsageObservation(providerAttemptId, State.STARTED, null);
    }

    public static ProviderUsageObservation reported(UUID providerAttemptId, long providerLlmTokens) {
        return new ProviderUsageObservation(providerAttemptId, State.REPORTED, providerLlmTokens);
    }

    public static ProviderUsageObservation missing(UUID providerAttemptId) {
        return new ProviderUsageObservation(providerAttemptId, State.MISSING, null);
    }

    public static ProviderUsageObservation failed(UUID providerAttemptId) {
        return new ProviderUsageObservation(providerAttemptId, State.FAILED, null);
    }

}
