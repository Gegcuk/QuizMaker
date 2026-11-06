package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;

/** Dark authenticated self-attempt boundary; no legacy, guest, operator or HTTP activation. */
public interface AttemptAccessService {
    enum Operation { VIEW, ANSWER, RESULT }

    /**
     * Checks current identity, immutable target binding, resource authority and the exact operation.
     * Joins the caller transaction; its authorization locks last until that transaction finishes.
     * Consumers must check inside their own read/write transaction, after acquiring any command
     * serialization lock, and enforce lifecycle, delivery, version and release rules separately.
     * Outside a caller transaction this is only a current access check, not a reusable capability.
     */
    Access requireAccess(UUID attemptId, Operation operation);

    /** Internal references for this invocation and operation only. Never accept this value from a client. */
    record Access(UUID attemptId, Operation operation, UUID quizId, UUID quizRevisionId,
                  UUID formId, UUID policySnapshotId, long stateVersion) {
        @Override public String toString() { return "AttemptAccess[operation=" + operation + "]"; }
    }
}
