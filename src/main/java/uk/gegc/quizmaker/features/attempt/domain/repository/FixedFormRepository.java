package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Internal metadata store; callers must join the application service transaction. No update/delete. */
public interface FixedFormRepository {
    void createOrReplay(UUID ownerId, FixedForm form);
    List<FixedForm> findByIds(UUID ownerId, Collection<UUID> formIds);
}
