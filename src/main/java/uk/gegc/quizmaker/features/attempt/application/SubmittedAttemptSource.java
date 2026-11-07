package uk.gegc.quizmaker.features.attempt.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubmissionRepository.Submission;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

/** Trusted internal grading boundary, never participant access or disclosure authority.
 * Requires the consumer transaction. Reads only committed terminal evidence and holds its
 * serialization lock until commit. No current authoring content or participant credentials.
 */
public interface SubmittedAttemptSource {
    Facts lock(UUID submissionId);

    record Facts(Submission submission, PolicySnapshot snapshot, FixedForm form, QuizRevision revision,
                 Map<UUID, ResolvedQuestionContracts> contracts, List<AcceptedResponseRepository.Value> responses) {
        public Facts { contracts = Map.copyOf(contracts); responses = List.copyOf(responses); }
        @Override public String toString() { return "SubmittedAttemptFacts[redacted]"; }
    }

    final class Failure extends RuntimeException {
        public enum Reason { INVALID_INPUT, NOT_FOUND, UNSUPPORTED, CORRUPT }
        private final Reason reason;
        public Failure(Reason reason) { super("Submitted attempt: " + reason, null, false, true); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
