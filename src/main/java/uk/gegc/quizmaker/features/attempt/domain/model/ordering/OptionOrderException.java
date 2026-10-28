package uk.gegc.quizmaker.features.attempt.domain.model.ordering;

/** Bounded internal failures; no identifiers, keys, content or SQL diagnostics. */
public final class OptionOrderException extends RuntimeException {
    public enum Reason { INVALID_ORDER, UNSUPPORTED_VERSION, UNSUPPORTED_FAMILY, ORDER_CONFLICT,
        INVALID_STORED_ORDER, PERSISTENCE_UNAVAILABLE }
    private final Reason reason;
    public OptionOrderException(Reason reason) { super("Option order rejected: " + reason); this.reason = reason; }
    public Reason reason() { return reason; }
    public static void require(boolean valid, Reason reason) { if (!valid) throw new OptionOrderException(reason); }
}
