package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.time.Instant;
import java.util.UUID;

/** Insert-only retained submission and durable finalization intent; no external dispatch. */
public interface AttemptSubmissionRepository {
    void insert(Submission value);
    void enqueue(Submission value);
    record Submission(UUID id, UUID attemptId, UUID formId, UUID policySnapshotId, UUID quizRevisionId,
                      UUID commandId, long stateVersion, int acceptedCount, int unansweredCount, Instant at) {
        @Override public String toString() { return "AttemptSubmission[redacted]"; }
    }
}
