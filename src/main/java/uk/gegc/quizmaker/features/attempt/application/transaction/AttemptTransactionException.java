package uk.gegc.quizmaker.features.attempt.application.transaction;

/** Internal composition failure, never a retryable command outcome or a database diagnostic. */
public final class AttemptTransactionException extends RuntimeException {
    public enum Reason { INCOMPATIBLE_ISOLATION, UNRESOLVABLE_ISOLATION, READ_ONLY }
    private final Reason reason;

    public AttemptTransactionException(Reason reason) {
        super("Attempt transaction rejected: " + java.util.Objects.requireNonNull(reason).name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static void preserve(RuntimeException failure) {
        if (failure instanceof AttemptTransactionException known) throw new AttemptTransactionException(known.reason());
    }
}
