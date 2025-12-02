package uk.gegc.quizmaker.features.auth.domain.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Domain event published when a new user is successfully registered.
 * <p>
 * This event is used to trigger side effects (e.g., registration bonus tokens)
 * after the user registration transaction has been committed, avoiding lock conflicts.
 * </p>
 */
public class UserRegisteredEvent extends ApplicationEvent {

    private final UUID userId;

    public UserRegisteredEvent(Object source, UUID userId) {
        super(source);
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}

