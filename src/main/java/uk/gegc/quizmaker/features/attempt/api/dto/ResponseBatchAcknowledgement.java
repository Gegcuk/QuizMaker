package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.BatchResult;

@Schema(name = "ResponseBatchAcknowledgement", description = "Accepted IDs in persisted form order, original final state version and replay marker; no feedback.")
public record ResponseBatchAcknowledgement(UUID attemptId, List<Item> responses, long stateVersion, boolean replayed) {
    @Schema(name = "AcceptedBatchResponse")
    public record Item(UUID formItemId, UUID acceptedResponseId) { }
    @Override public String toString() { return "ResponseBatchAcknowledgement[count=" + responses.size() + "]"; }
    public static ResponseBatchAcknowledgement from(BatchResult result) {
        return new ResponseBatchAcknowledgement(result.attemptId(), result.responses().stream()
                .map(i -> new Item(i.formItemId(), i.acceptedResponseId())).toList(), result.stateVersion(), result.replayed());
    }
}
