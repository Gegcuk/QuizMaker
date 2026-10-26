package uk.gegc.quizmaker.features.attempt.domain.model.command;

import java.util.Objects;

/** Internal parser failure; transport boundaries must map it without exposing resource existence. */
public final class AttemptCommandException extends RuntimeException {
    private final AttemptCommands.Reason reason;

    public AttemptCommandException(AttemptCommands.Reason reason) {
        super("Attempt command rejected: " + Objects.requireNonNull(reason));
        this.reason = reason;
    }

    public AttemptCommands.Reason reason() { return reason; }
}
