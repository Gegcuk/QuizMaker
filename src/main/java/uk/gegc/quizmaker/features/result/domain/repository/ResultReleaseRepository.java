package uk.gegc.quizmaker.features.result.domain.repository;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.result.domain.model.ResultRelease;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfiguration;

/** Requires the caller transaction; reads use current committed, locked evidence. */
public interface ResultReleaseRepository {
    Optional<UUID> submission(UUID attemptId);
    Optional<ResultRelease> find(UUID resultId);
    /** Captured scheduled work excludes the immediate release adapter. */
    boolean scheduled(UUID resultId);
    /** Bounded immutable captured rule; absence means no scheduled binding, never a mutable head. */
    Optional<ReleaseConfiguration> scheduledRule(UUID resultId);
    void insert(ResultRelease release);
}
