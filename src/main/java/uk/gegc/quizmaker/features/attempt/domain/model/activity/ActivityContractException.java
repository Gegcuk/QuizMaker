package uk.gegc.quizmaker.features.attempt.domain.model.activity;

import java.util.List;
import java.util.UUID;

/** Bounded internal failures; never retain input, response content or parser causes. */
public final class ActivityContractException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNKNOWN_KIND, UNKNOWN_VALUE, CONTRADICTORY_STATE,
        PRIVACY_NOTICE_REQUIRED, NOT_IN_PROGRESS, FINAL_SUBMIT_REQUIRED, PARTICIPATION_REQUIRED,
        NOT_GRADED
    }

    private final Reason reason;

    private final List<UUID> missingFormItemIds;

    public ActivityContractException(Reason reason) { this(reason, List.of()); }

    public ActivityContractException(Reason reason, List<UUID> missingFormItemIds) {
        super("Activity contract rejected: " + reason);
        this.reason = reason;
        this.missingFormItemIds = reason == Reason.PARTICIPATION_REQUIRED ? List.copyOf(missingFormItemIds) : List.of();
    }

    public Reason reason() { return reason; }

    public List<UUID> missingFormItemIds() { return missingFormItemIds; }

    public static void require(boolean valid, Reason reason) {
        if (!valid) throw new ActivityContractException(reason);
    }
}
