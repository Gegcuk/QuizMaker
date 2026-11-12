package uk.gegc.quizmaker.features.quiz.domain.repository;

import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal persistence port for an already authorized publication caller. Owner IDs are trusted
 * server context, never request authorization. There is deliberately no update/delete operation.
 */
public interface QuizRevisionRepository {
    /** Inserts the complete aggregate atomically; sections cannot be attached to an existing revision. */
    void insert(QuizRevision revision);

    List<QuizRevision> findByIds(UUID ownerId, Collection<UUID> revisionIds);

    /**
     * Current read of the entire immutable aggregate, joining the caller's transaction.
     * Callers needing exclusive question-contract locks must declare that scope first,
     * before this method takes shared locks on the question rows.
     */
    Optional<QuizRevision> findByIdForShare(UUID ownerId, UUID revisionId);

    default Optional<QuizRevision> findById(UUID ownerId, UUID revisionId) {
        return findByIds(ownerId, List.of(revisionId)).stream().findFirst();
    }
}
