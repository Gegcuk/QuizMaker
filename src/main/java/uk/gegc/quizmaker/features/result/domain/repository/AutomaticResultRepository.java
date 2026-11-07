package uk.gegc.quizmaker.features.result.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

/** Insert-only result and transactional outbox. No release, cleanup or external dispatch. */
public interface AutomaticResultRepository {
    Optional<AutomaticResult> find(UUID submissionId);
    void insert(AutomaticResult result);
    void enqueue(AutomaticResult result);
    List<UUID> pending(int limit);
}
