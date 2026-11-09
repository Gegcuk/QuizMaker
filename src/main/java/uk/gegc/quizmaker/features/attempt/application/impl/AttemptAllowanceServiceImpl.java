package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AttemptLimitPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;
import uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptAllowanceRepository;
import uk.gegc.quizmaker.features.attempt.infra.receipt.ReceiptDatabaseFailure;
import static uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.require;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AttemptAllowanceServiceImpl implements AttemptAllowanceService {
    private final AttemptAllowanceRepository repository;
    private final RetakeEligibilityService eligibility;
    private final AttemptEntryReader entries;

    public AttemptAllowanceServiceImpl(AttemptAllowanceRepository repository, RetakeEligibilityService eligibility,
                                       AttemptEntryReader entries) {
        this.repository = repository; this.eligibility = eligibility; this.entries = entries;
    }

    @Override public void check(Scope scope, AttemptLimitPolicy policy) {
        require(scope != null && scope.kind() == ContextKind.PUBLICATION && scope.subjectId() != null
                && scope.contextId() != null && scope.contextVersion() > 0 && policy != null, INVALID_INPUT);
        try {
            repository.lock(scope, policy);
            var entry = entries.read(scope);
            require(entry != null && entry.version() == 1 && entry.state() != null, ELIGIBILITY_UNAVAILABLE);
            var decision = eligibility.evaluate(new Context(1, scope, Access.VERIFIED, entry.state(),
                    new Policy(1, policy.maximumAttempts(), policy.cooldown()), repository.readCurrent(scope, policy)));
            if (decision.outcome() == Outcome.DENY) throw new AttemptStartException(switch (decision.reason()) {
                case ACTIVE_ATTEMPT -> ACTIVE_ATTEMPT;
                case LIMIT_EXHAUSTED -> LIMIT_EXHAUSTED;
                case COOLDOWN -> COOLDOWN;
                case ENTRY_CLOSED -> ENTRY_CLOSED;
                default -> ELIGIBILITY_UNAVAILABLE;
            }, decision.nextEligibleAt());
        } catch (DataAccessException failure) { throw storage(failure); }
    }

    @Override public void bind(Scope scope, UUID attemptId, UUID startEventId) {
        try { repository.bind(scope, attemptId, startEventId); }
        catch (DataAccessException failure) { throw storage(failure); }
    }

    private static AttemptStartException storage(RuntimeException failure) {
        return new AttemptStartException(switch (ReceiptDatabaseFailure.reason(failure)) {
            case RETRYABLE -> RETRYABLE;
            case IN_PROGRESS -> IN_PROGRESS;
            default -> PERSISTENCE_UNAVAILABLE;
        });
    }
}
