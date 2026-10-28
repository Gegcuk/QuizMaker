package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrderingPolicy;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Dark internal boundary for already authorized callers with trusted owner scope; no attempt/HTTP activation. */
public interface FormOptionOrderService {
    /** Form and complete option order join one transaction. External callers declare the complete #598 scope first. */
    FormOptionOrder createOrReplay(UUID ownerId, FixedForm form, AnswerOrderingPolicy policy);
    /** Persisted metadata only; follows transaction isolation and never regenerates order or evaluates responses. */
    List<FormOptionOrder> findByIds(UUID ownerId, Collection<UUID> formIds);
}
