package uk.gegc.quizmaker.features.attempt.application;

import java.util.Map;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

/** Internal evidence only. The caller must hold a transaction through projection/serialization mapping. */
public interface AttemptReadFactsService {
    enum Purpose { ACTIVE_DELIVERY, PARTICIPANT_VIEW }
    Facts read(UUID attemptId, Purpose purpose);

    record Facts(AttemptAccessService.Access access, ResolvedAttemptPolicy policy, FixedForm form,
                 FormOptionOrder order, QuizRevision revision, Map<UUID, ResolvedQuestionContracts> contracts) {
        public Facts { contracts = Map.copyOf(contracts); }
        @Override public String toString() { return "AttemptReadFacts"; }
    }
}
