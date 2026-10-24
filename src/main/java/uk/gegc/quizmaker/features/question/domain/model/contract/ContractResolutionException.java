package uk.gegc.quizmaker.features.question.domain.model.contract;

/** Stable internal failures; messages never contain content, answers or database diagnostics. */
public final class ContractResolutionException extends RuntimeException {
    public enum Reason {
        INVALID_REFERENCE, INVALID_BINDING, DUPLICATE_CONTRACT, UNSUPPORTED_CONTRACT,
        REVISION_UNAVAILABLE, UNBOUND_REVISION, BINDING_CONFLICT, INVALID_STORED_BINDING,
        PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public ContractResolutionException(Reason reason) {
        super("Question contract rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
