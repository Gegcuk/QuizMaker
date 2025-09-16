package uk.gegc.quizmaker.features.billing.testutils;

import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;

/**
 * Enum representing the billing state for state machine validation.
 * 
 * This enum provides a clean abstraction for state machine validation
 * that maps to the actual ReservationState enum but provides additional
 * utility methods for testing.
 */
public enum BillingState {
    NONE,
    RESERVED,
    COMMITTED,
    RELEASED,
    EXPIRED;

    /**
     * Converts from ReservationState to BillingState.
     * 
     * @param state The ReservationState
     * @return The corresponding BillingState
     */
    public static BillingState from(ReservationState state) {
        if (state == null) {
            return NONE;
        }
        
        return switch (state) {
            case ACTIVE -> RESERVED;
            case COMMITTED -> COMMITTED;
            case RELEASED -> RELEASED;
            case CANCELLED -> RELEASED; // Treat cancelled as released for state machine
            case EXPIRED -> EXPIRED;
        };
    }

    /**
     * Converts from BillingState to ReservationState.
     * 
     * @param state The BillingState
     * @return The corresponding ReservationState
     */
    public static ReservationState toReservationState(BillingState state) {
        if (state == null) {
            return null;
        }
        
        return switch (state) {
            case NONE -> null;
            case RESERVED -> ReservationState.ACTIVE;
            case COMMITTED -> ReservationState.COMMITTED;
            case RELEASED -> ReservationState.RELEASED;
            case EXPIRED -> ReservationState.EXPIRED;
        };
    }

    /**
     * Checks if this state represents an initial state.
     * 
     * @return true if this is an initial state
     */
    public boolean isInitial() {
        return this == NONE;
    }

    /**
     * Checks if this state represents a reserved state.
     * 
     * @return true if this is a reserved state
     */
    public boolean isReserved() {
        return this == RESERVED;
    }

    /**
     * Checks if this state represents a final state.
     * 
     * @return true if this is a final state
     */
    public boolean isFinal() {
        return this == COMMITTED || this == RELEASED || this == EXPIRED;
    }

    /**
     * Checks if this state represents a terminal state (cannot transition further).
     * 
     * @return true if this is a terminal state
     */
    public boolean isTerminal() {
        return this == COMMITTED || this == RELEASED || this == EXPIRED;
    }

    /**
     * Gets the display name for this state.
     * 
     * @return Display name
     */
    public String getDisplayName() {
        return switch (this) {
            case NONE -> "None";
            case RESERVED -> "Reserved";
            case COMMITTED -> "Committed";
            case RELEASED -> "Released";
            case EXPIRED -> "Expired";
        };
    }
}
