package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Committed, retained terminal evidence. Internal callers derive scope from the submission. */
public interface SubmittedAttemptRepository {
    Optional<Header> lock(UUID submissionId);
    List<AcceptedResponseRepository.Value> responses(UUID attemptId);
    record Header(AttemptSubmissionRepository.Submission submission, UUID revisionOwnerId,
                  UUID boundPolicyId, UUID boundFormId, int submissionVersion, int intentVersion,
                  int streamVersion, int subjectVersion, String subjectType) { }
}
