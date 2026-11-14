package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

/** Online authenticated flat immediate-acceptance submission. No answer payload or result disclosure.
 * <p>V2 composition requires actual READ_COMMITTED; standalone owners set it explicitly.
 * See {@link uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions}
 * for ownership, rejection and JPA caller obligations. Legacy contracts are separate.
 */
public interface AttemptSubmissionService {
    Result submit(Command command);
    record Command(UUID attemptId, long expectedStateVersion, boolean completionConfirmed, CommandReceipt.Key key) {
        @Override public String toString() { return "AttemptSubmissionCommand[redacted]"; }
    }
    record Result(UUID attemptId, UUID submissionId, long stateVersion, boolean replayed) { }
}
