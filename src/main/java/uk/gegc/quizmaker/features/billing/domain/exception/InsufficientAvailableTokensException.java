package uk.gegc.quizmaker.features.billing.domain.exception;

/**
 * Exception thrown when attempting to deduct tokens but the user has insufficient available tokens
 * and negative balances are not allowed.
 */
public class InsufficientAvailableTokensException extends RuntimeException {
    
    private final long requestedTokens;
    private final long availableTokens;
    private final long shortfall;

    public InsufficientAvailableTokensException(String message, long requestedTokens, long availableTokens, long shortfall) {
        super(message);
        this.requestedTokens = requestedTokens;
        this.availableTokens = availableTokens;
        this.shortfall = shortfall;
    }

    public long getRequestedTokens() {
        return requestedTokens;
    }

    public long getAvailableTokens() {
        return availableTokens;
    }

    public long getShortfall() {
        return shortfall;
    }
}
