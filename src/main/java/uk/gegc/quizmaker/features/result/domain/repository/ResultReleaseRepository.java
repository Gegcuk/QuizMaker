package uk.gegc.quizmaker.features.result.domain.repository;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.result.domain.model.ResultRelease;

/** Requires the caller transaction; reads use current committed, locked evidence. */
public interface ResultReleaseRepository {
    Optional<UUID> submission(UUID attemptId);
    Optional<ResultRelease> find(UUID resultId);
    void insert(ResultRelease release);
}
