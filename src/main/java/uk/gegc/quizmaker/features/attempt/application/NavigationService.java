package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

/** Authenticated flat navigation. Every invocation, including replay, rechecks current READ authority.
 * <p>V2 composition requires actual READ_COMMITTED; standalone owners set it explicitly.
 * See {@link uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions}
 * for ownership, rejection and JPA caller obligations. Legacy contracts are separate.
 */
public interface NavigationService {
    record Command(UUID attemptId, UUID formItemId, long expectedStateVersion, Long expectedNavigationVersion, CommandReceipt.Key key) {
        @Override public String toString() { return "NavigationCommand[redacted]"; }
    }
    record Result(UUID attemptId, UUID formItemId, long stateVersion, Cursor cursor, boolean replayed) { }
    record NextCommand(UUID attemptId, long expectedStateVersion, long expectedNavigationVersion, CommandReceipt.Key key) {
        @Override public String toString() { return "NextItemCommand[redacted]"; }
    }
    Result navigate(Command command);
    Result next(NextCommand command);
}
