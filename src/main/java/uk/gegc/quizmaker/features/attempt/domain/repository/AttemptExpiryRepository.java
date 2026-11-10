package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.attempt.application.AttemptExpiryService.Cursor;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

/** Transactional system work/receipt persistence. Never resolves a client-supplied identity. */
public interface AttemptExpiryRepository {
    List<Cursor> due(Instant now, int limit, Cursor after);
    Optional<UUID> lockSubject(UUID attemptId);
    boolean claim(UUID attemptId);
    Optional<Receipt> receipt(UUID attemptId);
    boolean voluntaryTerminal(UUID attemptId, long stateVersion, Instant completedAt);
    void record(UUID attemptId, Instant deadline, Instant recordedAt, UUID commandId, CommandReceipt.Acknowledgement acknowledgement);
    void removeWork(UUID attemptId);
    record Receipt(int version, UUID commandId, Instant deadline, CommandReceipt.Acknowledgement acknowledgement) {
        @Override public String toString() { return "ExpiryReceipt[redacted]"; }
    }
}
