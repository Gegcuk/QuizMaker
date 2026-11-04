package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.*;

import java.time.Instant;
import java.util.UUID;

/** Transactional persistence only; rows and context are verified before application use. */
public interface CommandReceiptRepository {
    Row reserve(Request request, UUID candidateId, Instant at);
    void complete(UUID id, Acknowledgement acknowledgement);
    Context context(UUID attemptId);
    int delete(UUID attemptId, int limit);

    record Row(UUID id, Request request, Instant recordedAt, boolean committed, Acknowledgement acknowledgement) {
        @Override public String toString() { return "ReceiptRow[redacted]"; }
    }
    record Context(UUID userId, long stateVersion, Instant terminalAt) { }
}
