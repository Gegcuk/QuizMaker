package uk.gegc.quizmaker.features.attempt.domain.model.activity;

/** Bounded internal failures; never retain input, response content or parser causes. */
public final class ActivityContractException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNKNOWN_KIND, UNKNOWN_VALUE, CONTRADICTORY_STATE,
        PRIVACY_NOTICE_REQUIRED, NOT_IN_PROGRESS, FINAL_SUBMIT_REQUIRED, PARTICIPATION_REQUIRED,
        NOT_GRADED
    }

    private final Reason reason;

    public ActivityContractException(Reason reason) {
        super("Activity contract rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static void require(boolean valid, Reason reason) {
        if (!valid) throw new ActivityContractException(reason);
    }
}
