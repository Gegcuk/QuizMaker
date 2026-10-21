package uk.gegc.quizmaker.features.quiz.application.query;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Optional status enrichment; only positions owned by username can be returned. */
public interface GenerationQueuePositionService {
    Map<UUID, GenerationQueuePosition> findPositions(Collection<UUID> jobIds, String username);
}
