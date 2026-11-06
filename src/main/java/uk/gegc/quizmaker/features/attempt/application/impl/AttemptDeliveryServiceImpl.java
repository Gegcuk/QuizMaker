package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.FormOptionOrderRepository;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.*;

@Service
public class AttemptDeliveryServiceImpl implements AttemptDeliveryService {
    private final AttemptAccessService access;
    private final PolicySnapshotService snapshots;
    private final FixedFormRepository forms;
    private final FormOptionOrderRepository orders;
    private final QuizRevisionRepository revisions;
    private final QuestionRevisionContractService contracts;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;

    public AttemptDeliveryServiceImpl(AttemptAccessService access, PolicySnapshotService snapshots,
            FixedFormRepository forms, FormOptionOrderRepository orders, QuizRevisionRepository revisions,
            QuestionRevisionContractService contracts, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.access = access; this.snapshots = snapshots; this.forms = forms; this.orders = orders;
        this.revisions = revisions; this.contracts = contracts; this.metrics = metrics;
        // Authorization and contract guards need locking reads. No authoritative INSERT/UPDATE/DELETE occurs.
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setReadOnly(true);
    }

    @Override public View read(UUID attemptId) {
        Timer.Sample timer = Timer.start();
        String outcome = "unavailable";
        try {
            var result = transaction.execute(status -> {
                try { return readAuthorized(attemptId); }
                catch (RuntimeException failure) { throw translate(failure); }
            });
            outcome = "delivered";
            return result;
        } catch (RuntimeException failure) {
            var safe = translate(failure);
            outcome = safe.reason().name().toLowerCase(Locale.ROOT);
            throw safe;
        } finally {
            try { timer.stop(Timer.builder("quizmaker.attempt.delivery")
                    .tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* telemetry cannot change delivery or expose content */ }
        }
    }

    private View readAuthorized(UUID attemptId) {
        var granted = access.requireAccess(attemptId, AttemptAccessService.Operation.VIEW);
        // Use the current locked state returned by #537, not an older caller snapshot.
        var state = granted.lifecycle().state();
        if (state.execution() != AttemptLifecycle.Execution.IN_PROGRESS) throw new AttemptDeliveryException(INACTIVE);
        var owner = granted.contentOwnerId();
        var saved = snapshots.read(owner, new PolicySnapshotReference.Stored(granted.policySnapshotId()));
        if (!(saved instanceof PolicySnapshot snapshot)) throw new AttemptDeliveryException(CORRUPT);
        var policy = snapshot.resolution().policy();
        if (policy.schemaVersion() != 1 || policy.delivery().mode() != DeliveryMode.ALL_AT_ONCE
                || policy.content().formMode() != FormMode.PRECOMPUTED
                || policy.content().sections() != SectionMode.IMPLICIT_FLAT) throw new AttemptDeliveryException(UNSUPPORTED);
        if (!policy.content().quizRevisionId().equals(granted.quizRevisionId())) throw new AttemptDeliveryException(CORRUPT);
        var form = forms.findByIds(owner, List.of(granted.formId()));
        var order = orders.findByIds(owner, List.of(granted.formId()));
        if (form.size() != 1 || order.size() != 1 || !order.get(0).policy().equals(policy.answerOrdering()))
            throw new AttemptDeliveryException(CORRUPT);
        // Reuse persisted bindings; declareScope locks but never creates or rebinds them.
        contracts.declareScope(owner, Set.of(granted.quizRevisionId()));
        var revision = revisions.findByIdForShare(owner, granted.quizRevisionId())
                .orElseThrow(() -> new AttemptDeliveryException(CORRUPT));
        var resolved = contracts.resolve(owner, revision.id());
        var participant = ParticipantFormProjector.project(form.get(0), revision, order.get(0), resolved);
        // No accepted-response reader/command is installed in this slice. Missing item/completion
        // facts remain unknown, never fabricated EMPTY values. #523 therefore advertises VIEW only.
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, policy, state,
                granted.stateVersion(), null, null);
        return new View(attemptId, granted.stateVersion(), participant, AttemptCommands.allowedActions(context).actions());
    }

    private static AttemptDeliveryException translate(RuntimeException failure) {
        if (failure instanceof AttemptDeliveryException delivery) return new AttemptDeliveryException(delivery.reason());
        if (failure instanceof AttemptAccessException denied) return new AttemptDeliveryException(switch (denied.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED;
            case DENIED -> NOT_FOUND;
            case IN_PROGRESS -> BUSY;
            case RETRYABLE, UNAVAILABLE -> UNAVAILABLE;
        });
        if (failure instanceof PolicySnapshotException snapshot) return new AttemptDeliveryException(switch (snapshot.reason()) {
            case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
            case UNSUPPORTED_VERSION, UNSUPPORTED_HASH_ALGORITHM -> UNSUPPORTED;
            default -> CORRUPT;
        });
        if (failure instanceof ContractResolutionException contract) return new AttemptDeliveryException(
                contract.reason() == ContractResolutionException.Reason.PERSISTENCE_UNAVAILABLE ? UNAVAILABLE : UNSUPPORTED);
        if (failure instanceof FixedFormException form && form.reason() == FixedFormException.Reason.PERSISTENCE_UNAVAILABLE
                || failure instanceof OptionOrderException order && order.reason() == OptionOrderException.Reason.PERSISTENCE_UNAVAILABLE)
            return new AttemptDeliveryException(UNAVAILABLE);
        if (failure instanceof DataAccessException || failure instanceof TransactionException || failure instanceof DatabaseConflictException) {
            var conflict = DatabaseConflictException.from(failure);
            return new AttemptDeliveryException(conflict.isPresent()
                    && conflict.get().kind() == DatabaseConflictException.Kind.LOCK_TIMEOUT ? BUSY : UNAVAILABLE);
        }
        return new AttemptDeliveryException(CORRUPT);
    }
}
