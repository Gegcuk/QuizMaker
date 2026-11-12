package uk.gegc.quizmaker.features.attempt.domain.model.response;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Immutable receipt projection: identifiers only, no answer or disclosure payload. */
public final class ResponseBatch {
    private ResponseBatch() { }
    public static UUID itemCommand(UUID commandId, UUID itemId) {
        return UUID.nameUUIDFromBytes(("response-batch-v1:" + commandId + ":" + itemId)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    public record AcceptedItem(UUID formItemId, UUID acceptedResponseId) { }
    public record BatchResult(UUID attemptId, List<AcceptedItem> responses, long stateVersion, boolean replayed) {
        public BatchResult {
            if (attemptId == null || responses == null || responses.isEmpty() || responses.size() > 500 || stateVersion < 1)
                throw new ResponseAcceptanceException(ResponseAcceptanceException.Reason.CORRUPT);
            var items = new HashSet<UUID>(); var answers = new HashSet<UUID>();
            for (var item : responses) if (item == null || item.formItemId() == null || item.acceptedResponseId() == null
                    || !items.add(item.formItemId()) || !answers.add(item.acceptedResponseId()))
                throw new ResponseAcceptanceException(ResponseAcceptanceException.Reason.CORRUPT);
            responses = List.copyOf(responses);
        }
        @Override public String toString() { return "ResponseBatchResult[count=" + responses.size() + "]"; }
    }
}
