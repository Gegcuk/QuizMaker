package uk.gegc.quizmaker.features.attempt.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

/** Immediate-lock, online, authenticated flat practice only; no grading or public activation. */
public interface ResponseAcceptanceService {
    Result accept(Command command);
    record Command(UUID attemptId, UUID formItemId, long expectedStateVersion, CommandReceipt.Key key,
                   QuestionType type, JsonNode response) {
        @Override public String toString() { return "ResponseAcceptanceCommand[redacted]"; }
    }
    record Result(UUID attemptId, UUID acceptedResponseId, long stateVersion, boolean replayed) { }
}
