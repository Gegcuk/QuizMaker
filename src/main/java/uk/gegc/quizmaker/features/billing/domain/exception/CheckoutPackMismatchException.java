package uk.gegc.quizmaker.features.billing.domain.exception;

public class CheckoutPackMismatchException extends RuntimeException {
    public CheckoutPackMismatchException(String message) {
        super(message);
    }
}
