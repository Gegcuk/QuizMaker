package uk.gegc.quizmaker.features.question.domain.repository;

import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Internal owner-scoped persistence port. The caller must already authorize publication/access. */
public interface QuestionContractBindingRepository {
    record Snapshot(Set<UUID> questionRevisionIds, Map<UUID, QuestionContractBinding> bindings) {
        public Snapshot {
            questionRevisionIds = Set.copyOf(questionRevisionIds);
            bindings = Map.copyOf(bindings);
        }
    }

    Optional<Snapshot> findForRevision(UUID ownerId, UUID quizRevisionId);

    /** Requires the caller's transaction; exposes no replace/delete operation. */
    void insert(UUID ownerId, UUID quizRevisionId, QuestionContractBinding binding);
}
