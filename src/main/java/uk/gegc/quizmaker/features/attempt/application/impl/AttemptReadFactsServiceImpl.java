package uk.gegc.quizmaker.features.attempt.application.impl;

import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import org.springframework.transaction.PlatformTransactionManager;
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
import uk.gegc.quizmaker.features.attempt.domain.repository.NavigationRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation;
import uk.gegc.quizmaker.features.attempt.domain.repository.FormOptionOrderRepository;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AttemptReadFactsServiceImpl implements AttemptReadFactsService {
    private final AttemptAccessService access;
    private final PlatformTransactionManager transactions;
    private final PolicySnapshotService snapshots;
    private final FixedFormRepository forms;
    private final FormOptionOrderRepository orders;
    private final QuizRevisionRepository revisions;
    private final QuestionRevisionContractService contracts;
    private final NavigationRepository navigation;
    private final AcceptedResponseRepository answers;
    private final AttemptTimingService timing;

    public AttemptReadFactsServiceImpl(AttemptAccessService access, PolicySnapshotService snapshots,
            FixedFormRepository forms, FormOptionOrderRepository orders, QuizRevisionRepository revisions,
            QuestionRevisionContractService contracts, NavigationRepository navigation, AcceptedResponseRepository answers, AttemptTimingService timing, PlatformTransactionManager transactions) {
        this.transactions = transactions;
        this.access = access; this.snapshots = snapshots; this.forms = forms; this.orders = orders;
        this.revisions = revisions; this.contracts = contracts; this.navigation = navigation; this.answers = answers; this.timing = timing;
    }

    @Override public Facts read(UUID attemptId, Purpose purpose) {
        AttemptTransactions.requireCompatible(transactions, false);
        try {
            var granted = access.requireAccess(attemptId, purpose == Purpose.ACCEPT_RESPONSE || purpose == Purpose.SUBMIT_ATTEMPT
                    || purpose == Purpose.DRAFT_COMMAND
                    ? AttemptAccessService.Operation.ANSWER : AttemptAccessService.Operation.VIEW);
            if (purpose != Purpose.PARTICIPANT_VIEW && granted.lifecycle().state().execution()
                    != uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution.IN_PROGRESS)
                throw new AttemptDeliveryException(INACTIVE);
            var owner = granted.contentOwnerId();
            var snapshot = snapshots.readCurrent(owner, granted.policySnapshotId());
            var policy = snapshot.resolution().policy();
            if (policy.schemaVersion() != 1 || policy.content().formMode() != FormMode.PRECOMPUTED
                    || policy.content().sections() != SectionMode.IMPLICIT_FLAT)
                throw new AttemptDeliveryException(UNSUPPORTED);
            if (!policy.content().quizRevisionId().equals(granted.quizRevisionId())) throw new AttemptDeliveryException(CORRUPT);
            // Preserve #524's lock order/current reads; freshness-only lock removal belongs to #906 phase B.
            contracts.declareScope(owner, Set.of(granted.quizRevisionId()));
            // An absent timing row can gap-lock under RR: acquire question locks first, as start does.
            var capturedTiming = timing.read(granted, snapshot);
            var form = forms.findByIdsForShare(owner, List.of(granted.formId()));
            var order = orders.findByIdsForShare(owner, List.of(granted.formId()));
            if (form.size() != 1 || order.size() != 1 || !order.get(0).policy().equals(policy.answerOrdering()))
                throw new AttemptDeliveryException(CORRUPT);
            var revision = revisions.findByIdForShare(owner, granted.quizRevisionId())
                    .orElseThrow(() -> new AttemptDeliveryException(CORRUPT));
            var resolved = contracts.resolve(owner, revision.id());
            var accepted = new java.util.HashSet<UUID>();
            if (policy.navigation().rules() != null) {
                var questions = revision.items().stream().collect(java.util.stream.Collectors.toMap(i -> i.id(), i -> i.question()));
                var items = form.get(0).items().stream().collect(java.util.stream.Collectors.toMap(i -> i.id(), i -> i.sourceItemId()));
                for (var ref : answers.references(attemptId)) {
                    var question = questions.get(items.get(ref.formItemId()));
                    if (!ref.formId().equals(granted.formId()) || question == null || !question.id().equals(ref.questionRevisionId())
                            || !resolved.get(question.id()).response().version().equals(ref.contract()) || !accepted.add(ref.formItemId()))
                        throw new AttemptDeliveryException(CORRUPT);
                }
            }
            var cursor = policy.navigation().mode() == NavigationMode.SEQUENTIAL
                    ? navigation.read(attemptId).orElseThrow(() -> new AttemptDeliveryException(CORRUPT)) : null;
            var nav = new FlatNavigation.Context(policy.navigation(), form.get(0).id(),
                    form.get(0).items().stream().map(uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm.Item::id).toList(), accepted, cursor);
            return new Facts(granted, policy, form.get(0), order.get(0), revision, resolved, snapshot.resolution().hash(), nav, capturedTiming);
        } catch (RuntimeException failure) {
            // Preserve the sanitized conflict until the caller chooses its command/read error contract.
            var conflict = DatabaseConflictException.from(failure);
            if (conflict.isPresent()) throw conflict.get();
            throw AttemptReadFailures.translate(failure);
        }
    }
}
