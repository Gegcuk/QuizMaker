package uk.gegc.quizmaker.model.quiz;

/**
 * Status of quiz generation jobs
 */
public enum GenerationStatus {
    
    /**
     * Job is waiting to be processed
     */
    PENDING("Pending"),
    
    /**
     * Job is currently being processed
     */
    PROCESSING("Processing"),
    
    /**
     * Job has completed successfully
     */
    COMPLETED("Completed"),
    
    /**
     * Job has failed
     */
    FAILED("Failed"),
    
    /**
     * Job has been cancelled
     */
    CANCELLED("Cancelled");

    private final String displayName;

    GenerationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Check if the status is a terminal state
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * Check if the status indicates the job is active
     */
    public boolean isActive() {
        return this == PENDING || this == PROCESSING;
    }

    /**
     * Check if the status indicates success
     */
    public boolean isSuccess() {
        return this == COMPLETED;
    }

    /**
     * Check if the status indicates failure
     */
    public boolean isFailure() {
        return this == FAILED || this == CANCELLED;
    }
} 