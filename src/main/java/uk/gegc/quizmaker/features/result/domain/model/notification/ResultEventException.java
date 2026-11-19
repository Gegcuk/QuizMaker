package uk.gegc.quizmaker.features.result.domain.model.notification;

/** Bounded errors only: no source, recipient, SQL or payload diagnostics. */
public final class ResultEventException extends RuntimeException {
    public enum Reason { INVALID_INPUT, UNSUPPORTED, CORRUPT, UNAVAILABLE }
    private final Reason reason;
    public ResultEventException(Reason reason) {
        super("Result notification event: " + reason, null, false, true);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean valid, Reason reason) {
        if (!valid) throw new ResultEventException(reason);
    }
}
