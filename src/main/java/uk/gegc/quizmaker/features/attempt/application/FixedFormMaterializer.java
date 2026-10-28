package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrderingPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;

import java.util.UUID;

/** Dark boundary for authorized server callers. Trusted owner scope is not participant authorization. */
public interface FixedFormMaterializer {
    record Command(FixedForm form, QuestionSelection.Result selection,
                   AuthoredQuestionOrder.Result questionOrder, AnswerOrderingPolicy answerOrdering) { }

    /**
     * Persist exact identity/order and resolve a safe presentation from retained immutable revisions.
     * Reuse all identities on retry, including after unknown commit outcome. Does not create an attempt.
     * Joins the caller transaction: the caller must first declare its complete #598 revision scope.
     * A successful return does not commit an outer transaction. No HTTP, start or offline activation.
     */
    ParticipantForm materialize(UUID ownerId, Command command);
}
