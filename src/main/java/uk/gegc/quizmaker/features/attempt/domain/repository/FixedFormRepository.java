package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;

import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;

import java.util.Collection;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Internal metadata store; callers must join the application service transaction. No update/delete. */
public interface FixedFormRepository {
    void createOrReplay(UUID ownerId, FixedForm form);
    /** Exact immutable metadata; requires the already persisted matching form in this transaction. */
    void createSelectionOrReplay(UUID ownerId, FixedForm form, QuestionSelection.Result selection);
    /** Absence identifies pre-extension forms; never infer a strategy or seed for them. */
    Optional<QuestionSelection.Evidence> findSelection(UUID ownerId, UUID formId);
    List<FixedForm> findByIds(UUID ownerId, Collection<UUID> formIds);

    /** Current shared reads of both roots and items in the caller transaction. */
    List<FixedForm> findByIdsForShare(UUID ownerId, Collection<UUID> formIds);
}
