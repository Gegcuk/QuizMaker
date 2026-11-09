package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.*;
import uk.gegc.quizmaker.features.attempt.application.AttemptReadFactsService.Facts;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;

/** Read/navigation actions only: VIEW facts do not prove authority to submit an answer. */
final class CurrentItemDelivery {
    private CurrentItemDelivery() { }
    static List<AttemptCommands.Type> actions(Facts facts) {
        var state = facts.access().lifecycle();
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, facts.policy(), state.state(), state.rowVersion(), null, null);
        var actions = new ArrayList<>(AttemptCommands.allowedActions(context).actions());
        if (state.state().execution() == uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution.IN_PROGRESS
                && facts.navigation().hasPermittedTransition()) actions.add(AttemptCommands.Type.NAVIGATE_TO_ITEM);
        return List.copyOf(actions);
    }
}
