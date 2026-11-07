package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;

/** Transactional insert-only evidence. Consuming application commands own authorization and guards. */
public interface AcceptedResponseRepository {
    boolean lockOwnBinding(UUID userId, UUID attemptId);
    boolean exists(UUID attemptId, UUID formItemId);
    /** Current locking metadata read; never loads answer payloads for completeness. */
    List<Reference> references(UUID attemptId);
    void insert(Value value);
    record Reference(UUID formId, UUID formItemId, UUID questionRevisionId, ContractVersion contract) { }
    record Value(UUID id, UUID attemptId, UUID formId, UUID formItemId, UUID questionRevisionId,
                 ContractVersion contract, String normalizedResponse, UUID commandId, Instant acceptedAt) {
        @Override public String toString() { return "AcceptedResponse[redacted]"; }
    }
}
