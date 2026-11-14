package uk.gegc.quizmaker.features.attempt.application;

import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;

/** Authenticated flat delivery. Reads committed availability without moving the cursor or activating routes.
 * <p>V2 composition requires actual READ_COMMITTED; standalone owners set it explicitly.
 * See {@link uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions}
 * for ownership, rejection and JPA caller obligations. Legacy contracts are separate.
 */
public interface AttemptDeliveryService {
    View read(UUID attemptId);

    record View(UUID attemptId, long stateVersion, ParticipantForm form, List<AttemptCommands.Type> allowedActions, uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor navigation) {
        public View(UUID attemptId, long stateVersion, ParticipantForm form, List<AttemptCommands.Type> allowedActions) {
            this(attemptId, stateVersion, form, allowedActions, null);
        }
        public View { allowedActions = List.copyOf(allowedActions); }
        @Override public String toString() { return "AttemptDeliveryView"; }
    }
}
