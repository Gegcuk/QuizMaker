package uk.gegc.quizmaker.features.billing.domain.exception;

import java.time.LocalDateTime;

public class InsufficientTokensException extends RuntimeException {
    
    private final long estimatedTokens;
    private final long availableTokens;
    private final long shortfall;
    private final LocalDateTime reservationTtl;

    public InsufficientTokensException(String message) {
        super(message);
        this.estimatedTokens = 0;
        this.availableTokens = 0;
        this.shortfall = 0;
        this.reservationTtl = null;
    }

    public InsufficientTokensException(String message, long estimatedTokens, long availableTokens, long shortfall, LocalDateTime reservationTtl) {
        super(message);
        this.estimatedTokens = estimatedTokens;
        this.availableTokens = availableTokens;
        this.shortfall = shortfall;
        this.reservationTtl = reservationTtl;
    }

    public long getEstimatedTokens() {
        return estimatedTokens;
    }

    public long getAvailableTokens() {
        return availableTokens;
    }

    public long getShortfall() {
        return shortfall;
    }

    public LocalDateTime getReservationTtl() {
        return reservationTtl;
    }
}

