package uk.gegc.quizmaker.features.attempt.application;

/** The expiry transition must commit before the legacy illegal-state response is returned. */
public final class AttemptTimedOutException extends IllegalStateException {
    public AttemptTimedOutException() {
        super("Attempt has timed out");
    }
}
