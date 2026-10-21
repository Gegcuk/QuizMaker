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
    void insert(QuizRevision revision);

    List<QuizRevision> findByIds(UUID ownerId, Collection<UUID> revisionIds);

    default Optional<QuizRevision> findById(UUID ownerId, UUID revisionId) {
        return findByIds(ownerId, List.of(revisionId)).stream().findFirst();
    }
}
