package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;

/** Insert-only evidence. Consumers use RC and hold this attempt's lifecycle lock through use;
 * terminal readers may rely on immutable submitted evidence. Authorization stays with the caller. */
public interface AcceptedResponseRepository {
    boolean lockOwnBinding(UUID userId, UUID attemptId);
    boolean exists(UUID attemptId, UUID formItemId);
    /** Bounded authoritative metadata read; no answer payloads or growing derived membership. */
    List<Reference> references(UUID attemptId);
    void insert(Value value);
    record Reference(UUID formId, UUID formItemId, UUID questionRevisionId, ContractVersion contract) { }
    record Value(UUID id, UUID attemptId, UUID formId, UUID formItemId, UUID questionRevisionId,
                 ContractVersion contract, String normalizedResponse, UUID commandId, Instant acceptedAt) {
        @Override public String toString() { return "AcceptedResponse[redacted]"; }
    }
}
