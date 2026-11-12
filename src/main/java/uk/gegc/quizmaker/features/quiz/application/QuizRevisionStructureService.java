package uk.gegc.quizmaker.features.quiz.application;

import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionReference;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionStructure;

import java.util.Optional;
import java.util.UUID;

/**
 * Internal adapter for an already authorized author/publication caller, like QuizRevisionRepository.
 * trustedOwnerId comes from that server boundary, never an API DTO. It is not an access grant.
 * No controller, participant route, publication command or mutable-quiz fallback is exposed.
 */
public interface QuizRevisionStructureService {
    Optional<RevisionStructure> read(UUID trustedOwnerId, RevisionReference reference);
}
