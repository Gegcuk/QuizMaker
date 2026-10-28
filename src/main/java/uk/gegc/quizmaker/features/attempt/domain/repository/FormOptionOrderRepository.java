package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Transactional immutable metadata port, with owner-scoped batched reads and no update/delete. */
public interface FormOptionOrderRepository {
    void createOrReplay(UUID ownerId, FixedForm form, FormOptionOrder order);
    List<FormOptionOrder> findByIds(UUID ownerId, Collection<UUID> formIds);
}
