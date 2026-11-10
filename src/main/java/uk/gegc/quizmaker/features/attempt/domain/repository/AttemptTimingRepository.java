package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTiming;

/** Insert-only checkpoint. Current reads join the existing command/access transaction. */
public interface AttemptTimingRepository {
    void insert(AttemptTiming timing);
    Optional<AttemptTiming> read(UUID attemptId);
}
