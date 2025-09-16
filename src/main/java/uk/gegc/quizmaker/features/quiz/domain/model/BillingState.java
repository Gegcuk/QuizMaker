package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Billing state for quiz generation jobs
 * Tracks the lifecycle of token reservations and commits
 */
public enum BillingState {

    /**
     * No billing activity (default state)
     */
    NONE("None"),

    /**
     * Tokens have been reserved for this job
     */
    RESERVED("Reserved"),

    /**
     * Tokens have been committed (spent) for this job
     */
    COMMITTED("Committed"),

    /**
     * Reserved tokens have been released (job failed/cancelled/expired)
     */
    RELEASED("Released");

    private final String displayName;

    BillingState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Check if the billing state indicates tokens are currently reserved
     */
    public boolean isReserved() {
        return this == RESERVED;
    }

    /**
     * Check if the billing state indicates tokens have been committed
     */
    public boolean isCommitted() {
        return this == COMMITTED;
    }

    /**
     * Check if the billing state indicates tokens have been released
     */
    public boolean isReleased() {
        return this == RELEASED;
    }

    /**
     * Check if the billing state indicates no billing activity
     */
    public boolean isNone() {
        return this == NONE;
    }

    /**
     * Check if the billing state is terminal (no further billing operations expected)
     */
    public boolean isTerminal() {
        return this == COMMITTED || this == RELEASED;
    }
}
