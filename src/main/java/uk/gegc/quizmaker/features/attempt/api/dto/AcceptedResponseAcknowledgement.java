package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.application.ResponseAcceptanceService;

@Schema(name = "AcceptedResponseAcknowledgement", description = "Committed acceptance references only; no correctness, score, key or explanation.")
public record AcceptedResponseAcknowledgement(UUID attemptId, UUID acceptedResponseId, long stateVersion, boolean replayed) {
    public static AcceptedResponseAcknowledgement from(ResponseAcceptanceService.Result value) {
        return new AcceptedResponseAcknowledgement(value.attemptId(), value.acceptedResponseId(), value.stateVersion(), value.replayed());
    }
}
