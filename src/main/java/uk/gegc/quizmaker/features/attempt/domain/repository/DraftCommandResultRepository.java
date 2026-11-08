package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.DraftCommandResult;

/** Writes join the command transaction; current reads resolve its original acknowledgement. */
public interface DraftCommandResultRepository {
    void insert(DraftCommandResult result);
    Optional<DraftCommandResult> read(UUID commandId);
}
