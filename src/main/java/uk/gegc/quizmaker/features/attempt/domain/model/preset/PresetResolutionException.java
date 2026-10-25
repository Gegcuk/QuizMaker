package uk.gegc.quizmaker.features.attempt.domain.model.preset;

/** Internal, content-free failures. No new HTTP error contract is introduced. */
public final class PresetResolutionException extends RuntimeException {
    public enum Reason { INVALID_REFERENCE, UNSUPPORTED_PRESET, INVALID_MAPPING_INPUT, DUPLICATE_POLICY_REFERENCE }

    private final Reason reason;

    public PresetResolutionException(Reason reason) {
        super("Attempt preset rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
