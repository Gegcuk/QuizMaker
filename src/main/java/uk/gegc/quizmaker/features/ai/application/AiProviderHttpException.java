package uk.gegc.quizmaker.features.ai.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe, provider-independent metadata for an HTTP failure returned by an AI provider.
 * Provider response bodies and messages must not be retained in this exception.
 */
public final class AiProviderHttpException extends RuntimeException {

    private final int statusCode;
    private final FailureKind failureKind;
    private final Duration retryAfter;

    public AiProviderHttpException(
            int statusCode,
            FailureKind failureKind,
            Duration retryAfter
    ) {
        super("AI provider request failed with " + failureKind + " (HTTP " + statusCode + ")");
        this.statusCode = statusCode;
        this.failureKind = Objects.requireNonNull(failureKind, "failureKind must not be null");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        this.retryAfter = retryAfter;
    }

    public int statusCode() {
        return statusCode;
    }

    public FailureKind failureKind() {
        return failureKind;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public enum FailureKind {
        REQUEST_TIMEOUT(true),
        CONFLICT(true),
        RATE_LIMIT(true),
        SERVER_ERROR(true),
        QUOTA_EXHAUSTED(false),
        CLIENT_ERROR(false);

        private final boolean retryable;

        FailureKind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
