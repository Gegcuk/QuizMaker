package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.ContentPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest.LaunchContext;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetReference;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.Key;

import java.util.UUID;

/** Internal authenticated ALL start. No controller, routing or participant delivery is activated. */
public interface AttemptStartService {
    /** Requested configuration is never authority; the server resolves user, access and publication. */
    record Command(int version, UUID quizId, ContentPolicy content, long publicationVersion, Key key,
                   PresetReference preset, LaunchContext context, String legacyMode, AnswerOrder answerOrder) {
        @Override public String toString() { return "AttemptStartCommand[redacted]"; }
    }

    /** References only. No answer material, role, capability or mutable draft is returned. */
    record Result(UUID attemptId, UUID formId, UUID quizRevisionId, long stateVersion, boolean replayed) { }

    /**
     * REQUIRED transaction, shared with all writers. When joining an outer transaction, return is
     * provisional until its commit. Retry unknown outcomes with the exact same key and command.
     * Access is rechecked on every call; a new publication invalidates fresh starts on the old
     * reference, but does not reinterpret an already committed receipt. No automatic retry/fallback.
     */
    Result start(Command command);
}
