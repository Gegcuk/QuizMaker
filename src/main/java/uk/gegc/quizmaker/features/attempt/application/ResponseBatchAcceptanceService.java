package uk.gegc.quizmaker.features.attempt.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.BatchResult;

/** Online ALL_AT_ONCE command; acceptance does not submit, grade or release an attempt. */
public interface ResponseBatchAcceptanceService {
    int MAX_ITEMS = 500;
    int MAX_BODY_BYTES = 5 * 1024 * 1024;
    int MAX_TEXT_BYTES = 32 * 1024;

    BatchResult acceptBatch(BatchCommand command);

    record BatchCommand(UUID attemptId, long expectedStateVersion, CommandReceipt.Key key, List<BatchItem> responses) {
        public BatchCommand { if (responses != null) responses = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(responses)); }
        @Override public String toString() { return "ResponseBatchCommand[redacted]"; }
    }
    record BatchItem(UUID formItemId, QuestionType type, JsonNode response) {
        @Override public String toString() { return "ResponseBatchItem[redacted]"; }
    }
}
