package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptLifecycleService;
import uk.gegc.quizmaker.features.attempt.application.AttemptReadFactsService;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.application.CommandReceiptService;
import uk.gegc.quizmaker.features.attempt.application.ExecutionTraceService;
import uk.gegc.quizmaker.features.attempt.application.StableResponseNormalizer;
import uk.gegc.quizmaker.features.attempt.application.UngradedActivityService;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.UngradedActivityRepository;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;

import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.require;

@Service
public class UngradedActivityServiceImpl implements UngradedActivityService {
    private final TransactionTemplate transactions;
    private final AuthenticatedSubjectResolver subjects;
    private final AcceptedResponseRepository bindings;
    private final AttemptLifecycleService lifecycle;
    private final AttemptReadFactsService facts;
    private final UngradedActivityRepository activities;
    private final CommandReceiptService receipts;
    private final ExecutionTraceService trace;
    private final Clock clock;
    private final MeterRegistry metrics;

    public UngradedActivityServiceImpl(PlatformTransactionManager manager, AuthenticatedSubjectResolver subjects,
            AcceptedResponseRepository bindings, AttemptLifecycleService lifecycle, AttemptReadFactsService facts,
            UngradedActivityRepository activities, CommandReceiptService receipts, ExecutionTraceService trace,
            Clock clock, MeterRegistry metrics) {
        this.transactions = AttemptTransactions.readCommitted(manager);
        this.subjects = subjects;
        this.bindings = bindings;
        this.lifecycle = lifecycle;
        this.facts = facts;
        this.activities = activities;
        this.receipts = receipts;
        this.trace = trace;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override public Participation accept(Response command) {
        require(command != null && command.formItemId() != null, INVALID_INPUT);
        return execute(command.attemptId(), command.expectedStateVersion(), command.key(), "response", context -> {
            var stable = StableResponseNormalizer.canonical(command.type(), command.value());
            require(command.value().size() == stable.size(), INVALID_INPUT);
            var prepared = AcceptedResponseWork.normalize(context.facts(), command.formItemId(), command.type(), stable);
            var input = JsonNodeFactory.instance.objectNode().put("activity", ActivityContract.Kind.UNGRADED.name());
            boolean retain = context.binding().policy().responseRetention()
                    == ActivityContract.ResponseRetention.RETAIN_IMMUTABLE_RESPONSES;
            if (retain) input.put("type", command.type().name()).set("response", stable);
            // In no-retention mode a valid response establishes participation. Its content does
            // not contribute to the stored command identity, even indirectly through a digest.
            var request = new CommandReceipt.Request(1, context.userId(), command.attemptId(),
                    AttemptCommands.Type.SUBMIT_ANSWER, command.formItemId(), command.expectedStateVersion(),
                    command.key(), CanonicalCommandInput.fingerprint(1, input));
            var result = withReceipt(request, id -> acceptFresh(context, prepared, command.expectedStateVersion(), id));
            var acknowledgement = result.receipt().acknowledgement();
            return new Participation(command.attemptId(), acknowledgement.resultId(), acknowledgement.stateVersion(), result.replayed());
        }, Participation::replayed);
    }

    @Override public Completion complete(Submit command) {
        require(command != null, INVALID_INPUT);
        return execute(command.attemptId(), command.expectedStateVersion(), command.key(), "complete", context -> {
            ActivityContractException.require(command.confirmed(), ActivityContractException.Reason.FINAL_SUBMIT_REQUIRED);
            var input = JsonNodeFactory.instance.objectNode().put("activity", ActivityContract.Kind.UNGRADED.name());
            var request = new CommandReceipt.Request(1, context.userId(), command.attemptId(),
                    AttemptCommands.Type.SUBMIT_ATTEMPT, command.attemptId(), command.expectedStateVersion(),
                    command.key(), CanonicalCommandInput.fingerprint(1, input));
            var result = withReceipt(request, id -> completeFresh(context, command.expectedStateVersion(), id));
            var acknowledgement = result.receipt().acknowledgement();
            var terminal = lifecycle.lock(command.attemptId());
            require(acknowledgement.resultId().equals(command.attemptId()) && terminal.state().terminal()
                    && terminal.rowVersion() == acknowledgement.stateVersion(), CORRUPT);
            var outcome = new ActivityContract.Completed(context.binding().policy().version(), terminal.submittedAt());
            return new Completion(command.attemptId(), outcome, terminal.rowVersion(), result.replayed());
        }, Completion::replayed);
    }

    private CommandReceipt.Acknowledgement acceptFresh(Context context, AcceptedResponseWork.Prepared prepared,
                                                        long expectedVersion, UUID commandId) {
        var current = context.facts().access().lifecycle();
        var retention = ActivityContract.responseDisposition(context.binding().policy(), inProgress(current));
        AcceptedResponseWork.requireAnswerAllowed(context.facts(), prepared.itemId(), expectedVersion, commandId,
                activities.participated(current.attemptId(), prepared.itemId()));
        var id = UUID.randomUUID();
        activities.insert(new UngradedActivityRepository.Participation(id, context.binding(), prepared.itemId(),
                prepared.questionId(), prepared.contract(),
                retention == ActivityContract.ResponseDisposition.RETAIN_IMMUTABLY ? prepared.normalized() : null,
                commandId, CommandReceipt.time(clock.instant())));
        var transition = AttemptLifecycle.evaluate(current.state(), AttemptLifecycle.Event.RESPONSE_RECORDED,
                AttemptLifecycle.Context.fresh());
        var next = advance(current, transition);
        append(context, commandId, prepared.itemId(), current, next, ExecutionTrace.Type.ANSWER_SUBMITTED,
                ExecutionTrace.ReasonCode.ACCEPTED);
        return new CommandReceipt.Acknowledgement(1, current.attemptId(), id, next.rowVersion());
    }

    private CommandReceipt.Acknowledgement completeFresh(Context context, long expectedVersion, UUID commandId) {
        var current = context.facts().access().lifecycle();
        require(current.rowVersion() == expectedVersion, STALE);
        var state = inProgress(current);
        var participated = activities.participatedItems(current.attemptId());
        var policy = context.binding().policy();
        ActivityContract.complete(policy, state,
                new ActivityContract.CompletionEvidence(true, new ArrayList<>(participated), current.updatedAt()));
        // ActivityContract has already applied the saved per-item requirements. The canonical
        // lifecycle owns the execution transition and its persisted completion timestamp.
        var completion = new CompletionFacts(1, CompletionFacts.FinalizationMode.LOCK_ON_ACCEPTANCE, false, true,
                policy.participation().items().stream().map(item -> new CompletionFacts.Item(item.formItemId(),
                        participated.contains(item.formItemId()) ? CompletionFacts.ResponseState.ACCEPTED
                                : CompletionFacts.ResponseState.EMPTY, CompletionFacts.Scope.APPLICABLE)).toList());
        var transition = AttemptLifecycle.evaluate(current.state(), AttemptLifecycle.Event.SUBMIT,
                AttemptLifecycle.Context.submit(completion));
        var next = advance(current, transition);
        append(context, commandId, null, current, next, ExecutionTrace.Type.ANSWER_LOCKED,
                ExecutionTrace.ReasonCode.LOCKED);
        append(context, commandId, null, current, next, ExecutionTrace.Type.ATTEMPT_SUBMITTED,
                ExecutionTrace.ReasonCode.SUBMITTED);
        return new CommandReceipt.Acknowledgement(1, current.attemptId(), current.attemptId(), next.rowVersion());
    }

    private static ActivityContract.Incomplete inProgress(LifecycleState current) {
        require(current.state().execution() == AttemptLifecycle.Execution.IN_PROGRESS && current.submittedAt() == null, INACTIVE);
        return new ActivityContract.Incomplete(ActivityContract.VERSION, ActivityContract.IncompleteState.IN_PROGRESS);
    }

    private LifecycleState advance(LifecycleState current, AttemptLifecycle.Decision decision) {
        require(decision instanceof AttemptLifecycle.Advance, CORRUPT);
        return lifecycle.save(current.attemptId(), current.rowVersion(), ((AttemptLifecycle.Advance) decision).state());
    }

    private void append(Context context, UUID commandId, UUID itemId, LifecycleState current, LifecycleState next,
                        ExecutionTrace.Type type, ExecutionTrace.ReasonCode reason) {
        var access = context.facts().access();
        var scope = new ExecutionTrace.Scope(access.attemptId(), access.policySnapshotId(), access.formId());
        trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), type, ExecutionTrace.Actor.PARTICIPANT,
                new ExecutionTrace.Data(commandId, itemId, current.state(), next.state(), reason)));
    }

    private record Context(UUID userId, AttemptReadFactsService.Facts facts, UngradedActivityRepository.Binding binding) { }

    private Context authorize(UUID attemptId) {
        var subject = subjects.resolve(attemptId);
        require(bindings.lockOwnBinding(subject.userId(), attemptId), NOT_FOUND);
        var locked = lifecycle.lock(attemptId);
        var saved = facts.read(attemptId, AttemptReadFactsService.Purpose.UNGRADED_COMMAND);
        require(locked.equals(saved.access().lifecycle()), CORRUPT);
        var binding = activities.read(attemptId, saved.form().id())
                .orElseThrow(() -> new ResponseAcceptanceException(CORRUPT));
        var expected = saved.form().items().stream().map(item -> item.id()).toList();
        require(!expected.isEmpty() && expected.equals(binding.policy().participation().items().stream()
                .map(ActivityContract.ItemRequirement::formItemId).toList()), CORRUPT);
        ActivityContract.start(binding.policy(), binding.startFacts());
        return new Context(subject.userId(), saved, binding);
    }

    private CommandReceipt.Result withReceipt(CommandReceipt.Request request, CommandReceiptService.Work fresh) {
        var invocation = new Invocation();
        try {
            // The enclosing RC transaction has already locked and authorized the exact attempt.
            // These locks are held through receipt lookup, new writes and commit.
            return receipts.execute(request, () -> { }, id -> {
                try {
                    return fresh.execute(id);
                } catch (ActivityContractException | ResponseAcceptanceException failure) {
                    invocation.failure = bounded(failure);
                    throw invocation.failure;
                }
            });
        } catch (ReceiptException denied) {
            if (invocation.failure != null && denied.reason() == ReceiptException.Reason.COMMAND_DENIED) {
                throw invocation.failure;
            }
            throw denied;
        }
    }

    private <T> T execute(UUID attemptId, long expectedVersion, CommandReceipt.Key key, String operation,
                          Function<Context, T> command, java.util.function.Predicate<T> replayed) {
        AttemptTransactions.requireCompatible(transactions.getTransactionManager(), true);
        require(attemptId != null && expectedVersion >= 0 && key != null, INVALID_INPUT);
        var invocation = new Invocation();
        String outcome = "unavailable";
        try {
            var result = transactions.execute(status -> {
                invocation.entered = true;
                try {
                    return command.apply(authorize(attemptId));
                } catch (RuntimeException failure) {
                    invocation.failure = bounded(failure);
                    throw invocation.failure;
                }
            });
            outcome = replayed.test(result) ? "replayed" : "returned";
            return result;
        } catch (RuntimeException failure) {
            var safe = failure == invocation.failure ? failure : new ResponseAcceptanceException(
                    invocation.entered ? UNKNOWN_OUTCOME : UNAVAILABLE);
            outcome = (safe instanceof ActivityContractException activity ? activity.reason().name()
                    : ((ResponseAcceptanceException) safe).reason().name()).toLowerCase(Locale.ROOT);
            throw safe;
        } finally {
            try { metrics.counter("quizmaker.attempt.ungraded", "version", "1", "operation", operation,
                    "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Optional telemetry never changes command authority. */ }
        }
    }

    private static RuntimeException bounded(RuntimeException failure) {
        if (failure instanceof ActivityContractException activity) {
            return new ActivityContractException(activity.reason());
        }
        return AttemptCommandFailures.translate(failure);
    }

    private static final class Invocation {
        boolean entered;
        RuntimeException failure;
    }
}
