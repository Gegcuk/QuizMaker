package uk.gegc.quizmaker.features.attempt.application;

import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;

/** Authenticated, flat ALL_AT_ONCE read. Does not create delivery state or activate commands. */
public interface AttemptDeliveryService {
    View read(UUID attemptId);

    record View(UUID attemptId, long stateVersion, ParticipantForm form, List<AttemptCommands.Type> allowedActions) {
        public View { allowedActions = List.copyOf(allowedActions); }
        @Override public String toString() { return "AttemptDeliveryView"; }
    }
}
