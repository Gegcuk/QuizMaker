package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import java.util.UUID;

/** Quiz-owned current access and immutable source for the dark owner/full-editor practice start. */
public interface QuizPracticeSourceService {
    record Access(UUID quizId, UUID ownerId, boolean owner, Boolean timerEnabled,
                  UUID currentRevisionId, long publicationVersion) { }

    /** Requires a caller transaction and a server-authenticated actor ID. Locks access through commit. */
    Access lockAccess(UUID trustedActorId, UUID quizId);

    /**
     * Current owner/full-editor authority plus ATTEMPT_READ or ATTEMPT_CREATE, in the caller transaction.
     * Returns the content owner. Does not require the attempt's revision to remain the publication head.
     * This is resource authority only; the caller must independently verify the attempt's own subject.
     */
    UUID lockParticipantAccess(UUID trustedActorId, UUID quizId, PermissionName operationPermission);

    /** Same transaction as lockAccess; this internal value must never be accepted from transport. */
    QuizRevision readRevision(Access access, UUID revisionId);
}
