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
    /**
     * Returns the verified immutable form after creation or identical replay. Callers should use
     * this result rather than reread it through a possibly older transaction snapshot.
     * Participates in an existing transaction: successful return does not commit that transaction,
     * and its later rollback still cancels a new form write.
     */
    FixedForm createOrReplay(UUID ownerId, FixedForm form);

    /**
     * Ordinary non-locking reads governed by the current transaction's isolation level. An older
     * REPEATABLE_READ snapshot may omit a form committed by another transaction even after an
     * identical createOrReplay succeeds. READ_COMMITTED and new transactions see committed forms.
     * A transaction sees its own new insert. Missing/foreign forms are omitted in request order.
     */
    List<FixedForm> findByIds(UUID ownerId, Collection<UUID> formIds);
}
