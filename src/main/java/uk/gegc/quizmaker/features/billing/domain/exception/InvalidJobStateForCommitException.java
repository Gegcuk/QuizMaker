package uk.gegc.quizmaker.features.billing.domain.exception;

import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;

/**
 * Exception thrown when attempting to commit tokens for a job in an invalid state.
 */
public class InvalidJobStateForCommitException extends RuntimeException {
    
    private final BillingState currentState;
    private final String jobId;

    public InvalidJobStateForCommitException(String jobId, BillingState currentState) {
        super(String.format("Cannot commit tokens for job %s in state %s. Job must be in RESERVED state.", 
                jobId, currentState));
        this.jobId = jobId;
        this.currentState = currentState;
    }

    public InvalidJobStateForCommitException(String jobId, BillingState currentState, String message) {
        super(String.format("Cannot commit tokens for job %s in state %s. %s", 
                jobId, currentState, message));
        this.jobId = jobId;
        this.currentState = currentState;
    }

    public BillingState getCurrentState() {
        return currentState;
    }

    public String getJobId() {
        return jobId;
    }
}
