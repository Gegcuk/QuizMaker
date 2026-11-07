package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.FormOptionOrderRepository;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AttemptReadFactsServiceImpl implements AttemptReadFactsService {
    private final AttemptAccessService access;
    private final PolicySnapshotService snapshots;
    private final FixedFormRepository forms;
    private final FormOptionOrderRepository orders;
    private final QuizRevisionRepository revisions;
    private final QuestionRevisionContractService contracts;

    public AttemptReadFactsServiceImpl(AttemptAccessService access, PolicySnapshotService snapshots,
            FixedFormRepository forms, FormOptionOrderRepository orders, QuizRevisionRepository revisions,
            QuestionRevisionContractService contracts) {
        this.access = access; this.snapshots = snapshots; this.forms = forms; this.orders = orders;
        this.revisions = revisions; this.contracts = contracts;
    }

    @Override public Facts read(UUID attemptId, Purpose purpose) {
        try {
            var granted = access.requireAccess(attemptId, purpose == Purpose.ACCEPT_RESPONSE
                    ? AttemptAccessService.Operation.ANSWER : AttemptAccessService.Operation.VIEW);
            if (purpose != Purpose.PARTICIPANT_VIEW && granted.lifecycle().state().execution()
                    != uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution.IN_PROGRESS)
                throw new AttemptDeliveryException(INACTIVE);
            var owner = granted.contentOwnerId();
            var policy = snapshots.readCurrent(owner, granted.policySnapshotId()).resolution().policy();
            if (policy.schemaVersion() != 1 || policy.delivery().mode() != DeliveryMode.ALL_AT_ONCE
                    || policy.content().formMode() != FormMode.PRECOMPUTED
                    || policy.content().sections() != SectionMode.IMPLICIT_FLAT)
                throw new AttemptDeliveryException(UNSUPPORTED);
            if (!policy.content().quizRevisionId().equals(granted.quizRevisionId())) throw new AttemptDeliveryException(CORRUPT);
            // Preserve #524's lock order and current reads, including all child rows in an older RR caller.
            contracts.declareScope(owner, Set.of(granted.quizRevisionId()));
            var form = forms.findByIdsForShare(owner, List.of(granted.formId()));
            var order = orders.findByIdsForShare(owner, List.of(granted.formId()));
            if (form.size() != 1 || order.size() != 1 || !order.get(0).policy().equals(policy.answerOrdering()))
                throw new AttemptDeliveryException(CORRUPT);
            var revision = revisions.findByIdForShare(owner, granted.quizRevisionId())
                    .orElseThrow(() -> new AttemptDeliveryException(CORRUPT));
            return new Facts(granted, policy, form.get(0), order.get(0), revision, contracts.resolve(owner, revision.id()));
        } catch (RuntimeException failure) {
            // Preserve the sanitized conflict until the caller chooses its command/read error contract.
            var conflict = DatabaseConflictException.from(failure);
            if (conflict.isPresent()) throw conflict.get();
            throw AttemptReadFailures.translate(failure);
        }
    }
}
