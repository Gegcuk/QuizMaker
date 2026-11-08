package uk.gegc.quizmaker.features.attempt.application;

import java.util.Map;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

/**
 * Internal evidence only. The caller must hold a transaction through projection/serialization mapping.
 * Sanitized database conflicts reach the caller, which owns its outward command or read error contract.
 */
public interface AttemptReadFactsService {
    enum Purpose { ACTIVE_DELIVERY, PARTICIPANT_VIEW, ACCEPT_RESPONSE, SUBMIT_ATTEMPT, DRAFT_COMMAND, NAVIGATION }
    Facts read(UUID attemptId, Purpose purpose);

    record Facts(AttemptAccessService.Access access, ResolvedAttemptPolicy policy, FixedForm form,
                 FormOptionOrder order, QuizRevision revision, Map<UUID, ResolvedQuestionContracts> contracts, String policyHash, uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Context navigation) {
        public Facts(AttemptAccessService.Access access, ResolvedAttemptPolicy policy, FixedForm form,
                     FormOptionOrder order, QuizRevision revision, Map<UUID, ResolvedQuestionContracts> contracts, String policyHash) {
            this(access, policy, form, order, revision, contracts, policyHash,
                    new uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Context(
                            policy.navigation(), form.id(), form.items().stream().map(FixedForm.Item::id).toList(), java.util.Set.of(), null));
        }
        public Facts { contracts = Map.copyOf(contracts); }
        public Facts(AttemptAccessService.Access access, ResolvedAttemptPolicy policy, FixedForm form,
                     FormOptionOrder order, QuizRevision revision, Map<UUID, ResolvedQuestionContracts> contracts) {
            this(access, policy, form, order, revision, contracts, null);
        }
        @Override public String toString() { return "AttemptReadFacts"; }
    }
}
