package uk.gegc.quizmaker.features.quiz.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Set;

/** Current source scopes, including retired quizzes; a destination link is not a reuse grant. */
public interface QuestionSourceScopeRepository {
    List<SourceScope> lockSources(Collection<UUID> questionIds);
    Set<String> lockPermissionNames(UUID actorId);

    record SourceScope(UUID questionId, UUID quizId, UUID ownerId) {
        public boolean ownedBy(UUID actorId) {
            return actorId != null && quizId != null && actorId.equals(ownerId);
        }
    }
}
