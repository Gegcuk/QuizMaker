package uk.gegc.quizmaker.features.quiz.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Set;

/** Durable source authority, independent of current or former quiz memberships. */
public interface QuestionSourceScopeRepository {
    List<SourceScope> lockSources(Collection<UUID> questionIds);
    Set<String> lockPermissionNames(UUID actorId);

    Optional<UUID> lockActor(String principal);
    boolean lockActiveOwner(UUID ownerId);
    List<SourceScope> lockForConfirmation(Collection<UUID> questionIds);
    void confirmLegacy(UUID questionId, UUID ownerId, UUID moderatorId, Instant at, String evidence);

    record SourceScope(UUID questionId, UUID ownerId) {
        public boolean ownedBy(UUID actorId) {
            return actorId != null && actorId.equals(ownerId);
        }
    }
}
