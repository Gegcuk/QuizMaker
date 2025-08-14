package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Enumeration of share link event types for analytics tracking.
 */
public enum ShareLinkEventType {
    
    /**
     * Share link was accessed/viewed
     */
    VIEW,
    
    /**
     * Share link was used to start a quiz attempt
     */
    ATTEMPT_START,
    
    /**
     * Share link was consumed (one-time token)
     */
    CONSUMED,
    
    /**
     * Share link was created
     */
    CREATED,
    
    /**
     * Share link was revoked
     */
    REVOKED
}
