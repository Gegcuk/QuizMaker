package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Dark internal boundary for already authorized server callers. ownerId is trusted scope,
 * never a client authorization claim. Does not create an attempt or resolve participant access.
 */
public interface FixedFormService {
    FixedForm createOrReplay(UUID ownerId, FixedForm form);
    List<FixedForm> findByIds(UUID ownerId, Collection<UUID> formIds);
}
