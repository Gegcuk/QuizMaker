package uk.gegc.quizmaker.features.attempt.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.Key;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

/** Online private drafts only. Every invocation, including replay, revalidates current authority.
 * <p>V2 composition requires actual READ_COMMITTED; standalone owners set it explicitly.
 * See {@link uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions}
 * for ownership, rejection and JPA caller obligations. Legacy contracts are separate.
 */
public interface DraftCommandService {
    record Command(Type operation, UUID attemptId, UUID formItemId, long expectedStateVersion,
                   long expectedDraftVersion, Key key, QuestionType type, JsonNode response) {
        @Override public String toString() { return "DraftCommand[redacted]"; }
    }
    record Result(UUID attemptId, UUID formItemId, long stateVersion, long draftVersion, AnswerDraft.Status status, boolean replayed) { }
    /**
     * Same semantic key after unknown commit; no automatic retry. Never changes lifecycle or accepted answers.
     * Joins an outer transaction when present; its result remains provisional until that transaction commits.
     */
    Result execute(Command command);
}
