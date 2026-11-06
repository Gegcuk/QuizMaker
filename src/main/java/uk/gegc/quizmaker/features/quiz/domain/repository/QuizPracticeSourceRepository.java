package uk.gegc.quizmaker.features.quiz.domain.repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Scalar locking reads; no role names or cached entity relationships establish authority. */
public interface QuizPracticeSourceRepository {
    record Source(UUID ownerId, Boolean timerEnabled, UUID currentRevisionId, long publicationVersion) { }
    boolean lockActiveUser(UUID userId);
    Set<String> lockPermissions(UUID userId);
    Optional<Source> lockSource(UUID quizId);
}
