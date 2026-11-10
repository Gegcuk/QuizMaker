package uk.gegc.quizmaker.features.attempt.application;

import java.util.Map;
import java.time.Instant;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTiming;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
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
    /** Receipt time comes from the command's injected Clock before any database lock wait. */
    default Facts read(UUID attemptId, Purpose purpose, Instant serverReceivedAt) {
        var saved = read(attemptId, purpose);
        if (saved.policy().timing().mode() != ResolvedAttemptPolicy.TimingMode.NONE) {
            if (saved.timing() == null) throw new AttemptDeliveryException(AttemptDeliveryException.Reason.CORRUPT);
            if (saved.timing().decide(serverReceivedAt) != AttemptTiming.Decision.OPEN)
                throw new AttemptDeliveryException(AttemptDeliveryException.Reason.INACTIVE);
        }
        return saved;
    }

    record Facts(AttemptAccessService.Access access, ResolvedAttemptPolicy policy, FixedForm form,
                 FormOptionOrder order, QuizRevision revision, Map<UUID, ResolvedQuestionContracts> contracts, String policyHash, uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Context navigation, AttemptTiming timing) {
        public Facts(AttemptAccessService.Access access, ResolvedAttemptPolicy policy, FixedForm form,
                     FormOptionOrder order, QuizRevision revision, Map<UUID, ResolvedQuestionContracts> contracts, String policyHash,
                     uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Context navigation) {
            this(access, policy, form, order, revision, contracts, policyHash, navigation, null);
        }
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
        public boolean oneAtATime() { return policy.delivery().mode() == ResolvedAttemptPolicy.DeliveryMode.ONE_AT_A_TIME; }
        public boolean current(UUID item) { return !oneAtATime() || navigation.cursor().currentFormItemId().equals(item); }
        public boolean visible(UUID item) { return current(item) && navigation.readable(item); }
        @Override public String toString() { return "AttemptReadFacts"; }
    }
}
