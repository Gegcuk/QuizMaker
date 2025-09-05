package uk.gegc.quizmaker.features.billing.domain.exception;

public class ReservationNotActiveException extends RuntimeException {
    public ReservationNotActiveException(String message) { super(message); }
}

