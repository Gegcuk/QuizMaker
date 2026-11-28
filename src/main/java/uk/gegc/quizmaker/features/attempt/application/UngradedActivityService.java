package uk.gegc.quizmaker.features.attempt.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.Completed;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.Key;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

/**
 * Internal authenticated response and explicit completion commands for a saved ungraded activity.
 * Authority is checked on every invocation. In a writable outer READ_COMMITTED transaction the
 * acknowledgements remain provisional until the caller commits.
 */
public interface UngradedActivityService {
    Participation accept(Response command);
    Completion complete(Submit command);

    record Response(UUID attemptId, UUID formItemId, long expectedStateVersion, Key key,
                    QuestionType type, JsonNode value) {
        @Override public String toString() { return "UngradedResponseCommand[redacted]"; }
    }

    record Submit(UUID attemptId, long expectedStateVersion, Key key, boolean confirmed) {
        public Submit(UUID attemptId, long expectedStateVersion, Key key) {
            this(attemptId, expectedStateVersion, key, true);
        }
        @Override public String toString() { return "UngradedSubmitCommand[redacted]"; }
    }

    record Participation(UUID attemptId, UUID participationId, long stateVersion, boolean replayed) { }
    record Completion(UUID attemptId, Completed outcome, long stateVersion, boolean replayed) { }
}
