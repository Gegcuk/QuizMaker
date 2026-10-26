package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectLookup;

import java.util.UUID;

/**
 * Dark internal storage for trusted server callers. #536 resolves authenticated identity;
 * #537 checks attempt access. Neither a supplied user ID nor a stored binding grants access.
 */
public interface AttemptSubjectService {
    /**
     * trustedUserId comes from the server authentication boundary, never a request field.
     * Rejects a binding that disagrees with it. Exact replay returns the verified stored value.
     * Joins the caller's transaction: a later outer rollback also rolls back a new binding.
     * #600 owns atomic creation with the target attempt, not a legacy attempts-table FK.
     */
    AttemptSubject bindOrReplay(UUID trustedUserId, AttemptSubject subject);

    /**
     * Internal evidence read, not an authorization check. Never infers a subject from legacy
     * user/sentinel/null rows. An ordinary read honors the caller's transaction isolation;
     * use bindOrReplay's return value after a replay in an older REPEATABLE_READ snapshot.
     * Retained bindings remain readable as evidence after a user is deactivated/soft-deleted.
     */
    AttemptSubjectLookup read(UUID attemptId);
}
