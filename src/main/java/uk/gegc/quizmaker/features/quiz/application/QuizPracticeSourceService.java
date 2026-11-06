package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import java.util.UUID;

/** Quiz-owned current access and immutable source for the dark owner/full-editor practice start. */
public interface QuizPracticeSourceService {
    record Access(UUID quizId, UUID ownerId, boolean owner, Boolean timerEnabled,
                  UUID currentRevisionId, long publicationVersion) { }

    /** Requires a caller transaction and a server-authenticated actor ID. Locks access through commit. */
    Access lockAccess(UUID trustedActorId, UUID quizId);

    /** Same transaction as lockAccess; this internal value must never be accepted from transport. */
    QuizRevision readRevision(Access access, UUID revisionId);
}
