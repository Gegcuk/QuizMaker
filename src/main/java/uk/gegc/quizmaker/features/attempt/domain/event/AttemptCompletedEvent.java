package uk.gegc.quizmaker.features.attempt.domain.event;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when an attempt is successfully completed.
 * <p>
 * This event is used to trigger analytics snapshot updates and other side effects
 * (e.g., notifications, achievements) without coupling the completion logic to these concerns.
 * </p>
 * <p>
 * Event is published synchronously by default. For heavy processing, consider configuring
 * async event listeners.
 * </p>
 */
public class AttemptCompletedEvent extends ApplicationEvent {

    private final UUID attemptId;
    private final UUID quizId;
    private final UUID userId;
    private final Instant completedAt;

    public AttemptCompletedEvent(Object source, UUID attemptId, UUID quizId, UUID userId, Instant completedAt) {
        super(source);
        this.attemptId = attemptId;
        this.quizId = quizId;
        this.userId = userId;
        this.completedAt = completedAt;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getQuizId() {
        return quizId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}

