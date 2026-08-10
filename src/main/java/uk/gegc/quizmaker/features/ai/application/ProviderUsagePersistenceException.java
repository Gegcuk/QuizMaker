package uk.gegc.quizmaker.features.ai.application;

/**
 * Stops provider retries when a completed attempt cannot be accounted for.
 */
public class ProviderUsagePersistenceException extends RuntimeException {

    public ProviderUsagePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
