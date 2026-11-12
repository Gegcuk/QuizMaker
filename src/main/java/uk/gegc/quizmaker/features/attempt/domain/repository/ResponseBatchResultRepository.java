package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.BatchResult;

/** Receipt-lifetime acknowledgement projection; accepted responses remain the authoritative evidence. */
public interface ResponseBatchResultRepository {
    void insert(UUID commandId, BatchResult result);
    BatchResult read(UUID commandId, UUID attemptId, long stateVersion);
}
