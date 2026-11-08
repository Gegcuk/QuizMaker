package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.*;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
import static uk.gegc.quizmaker.features.attempt.domain.model.navigation.NavigationException.*;

@Service
public class NavigationServiceImpl implements NavigationService {
    private final AuthenticatedSubjectResolver identity;
    private final AttemptAccessService access;
    private final AttemptReadFactsService facts;
    private final AttemptLifecycleService lifecycle;
    private final AcceptedResponseRepository answers;
    private final NavigationRepository navigation;
    private final CommandReceiptService receipts;
    private final ExecutionTraceService trace;
    private final TransactionTemplate read;
    private final MeterRegistry metrics;

    public NavigationServiceImpl(AuthenticatedSubjectResolver identity, AttemptAccessService access, AttemptReadFactsService facts,
            AttemptLifecycleService lifecycle, AcceptedResponseRepository answers, NavigationRepository navigation,
            CommandReceiptService receipts, ExecutionTraceService trace, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.identity = identity; this.access = access; this.facts = facts; this.lifecycle = lifecycle; this.answers = answers;
        this.navigation = navigation; this.receipts = receipts; this.trace = trace; this.metrics = metrics;
        this.read = new TransactionTemplate(manager); this.read.setReadOnly(true);
    }

    @Override public Result navigate(Command c) {
        String outcome = "unavailable";
        try {
            require(c != null && c.attemptId() != null && c.formItemId() != null && c.expectedStateVersion() >= 0
                    && c.key() != null && (c.expectedNavigationVersion() == null || c.expectedNavigationVersion() > 0), Reason.INVALID_INPUT);
            var subject = identity.resolve(c.attemptId());
            var input = JsonNodeFactory.instance.objectNode();
            if (c.expectedNavigationVersion() != null) input.put("expectedNavigationVersion", c.expectedNavigationVersion());
            var request = new CommandReceipt.Request(1, subject.userId(), c.attemptId(), AttemptCommands.Type.NAVIGATE_TO_ITEM,
                    c.formItemId(), c.expectedStateVersion(), c.key(), CanonicalCommandInput.fingerprint(1, input));
            class Invocation {
                NavigationException failure;
                <T> T checked(Supplier<T> action) {
                    try { return action.get(); } catch (RuntimeException rejected) { failure = safe(rejected); throw failure; }
                }
            }
            var call = new Invocation();
            CommandReceipt.Result executed;
            try {
                executed = receipts.execute(request, () -> call.checked(() -> {
                    require(answers.lockOwnBinding(subject.userId(), c.attemptId()), Reason.NOT_FOUND);
                    lifecycle.lock(c.attemptId());
                    return access.requireAccess(c.attemptId(), AttemptAccessService.Operation.VIEW);
                }), id -> call.checked(() -> move(c, id)));
            } catch (ReceiptException failure) {
                if (call.failure != null && (failure.reason() == ReceiptException.Reason.ACCESS_DENIED
                        || failure.reason() == ReceiptException.Reason.COMMAND_DENIED)) throw call.failure;
                throw failure;
            }
            var ack = executed.receipt().acknowledgement();
            var result = read.execute(s -> call.checked(() -> navigation.result(ack.resultId())
                    .orElseThrow(() -> new NavigationException(Reason.CORRUPT))));
            require(result.commandId().equals(executed.receipt().id()) && result.attemptId().equals(c.attemptId())
                    && result.targetFormItemId().equals(c.formItemId()) && ack.attemptId().equals(c.attemptId())
                    && ack.stateVersion() == c.expectedStateVersion(), Reason.CORRUPT);
            var cursor = result.cursor();
            require(cursor == null ? c.expectedNavigationVersion() == null
                    : cursor.currentFormItemId().equals(c.formItemId()) && c.expectedNavigationVersion() != null
                    && (cursor.version() == c.expectedNavigationVersion() || c.expectedNavigationVersion() < Long.MAX_VALUE
                        && cursor.version() == c.expectedNavigationVersion() + 1), Reason.CORRUPT);
            outcome = executed.replayed() ? "replayed" : "navigated";
            return new Result(result.attemptId(), result.targetFormItemId(), ack.stateVersion(), cursor, executed.replayed());
        } catch (RuntimeException failure) {
            var safe = safe(failure); outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { metrics.counter("quizmaker.attempt.navigation", "version", "1", "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* telemetry never changes command authority */ }
        }
    }

    private CommandReceipt.Acknowledgement move(Command c, UUID commandId) {
        var saved = facts.read(c.attemptId(), AttemptReadFactsService.Purpose.NAVIGATION);
        ParticipantFormProjector.project(saved.form(), saved.revision(), saved.order(), saved.contracts());
        require(saved.access().stateVersion() == c.expectedStateVersion(), Reason.STALE);
        saved.navigation().check(c.formItemId(), c.expectedNavigationVersion());
        var state = saved.access().lifecycle();
        var decision = AttemptCommands.evaluate(new AttemptCommands.Command(1, AttemptCommands.Type.NAVIGATE_TO_ITEM),
                new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), state.state(), state.rowVersion(),
                        new AttemptCommands.ItemFacts(true, true, answers.exists(c.attemptId(), c.formItemId())
                                ? uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.ResponseState.ACCEPTED
                                : uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.ResponseState.EMPTY,
                                c.formItemId(), saved.navigation()), null),
                new AttemptCommands.Metadata(c.expectedStateVersion(), commandId,
                        uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.CommandIdentity.FRESH));
        require(decision.outcome() == AttemptCommands.Outcome.ALLOWED, Reason.CORRUPT);
        Cursor cursor = saved.navigation().cursor();
        if (cursor != null && !cursor.currentFormItemId().equals(c.formItemId())) {
            require(cursor.version() < Long.MAX_VALUE && navigation.move(c.attemptId(), cursor.version(), c.formItemId()), Reason.STALE);
            cursor = new Cursor(cursor.formId(), c.formItemId(), cursor.version() + 1);
        }
        navigation.record(new NavigationRepository.Result(commandId, c.attemptId(), c.formItemId(), cursor));
        trace.append(new ExecutionTrace.Scope(c.attemptId(), saved.access().policySnapshotId(), saved.form().id()),
                new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.ITEM_NAVIGATED, ExecutionTrace.Actor.PARTICIPANT,
                        new ExecutionTrace.Data(commandId, c.formItemId(), null, null, ExecutionTrace.ReasonCode.NAVIGATED)));
        return new CommandReceipt.Acknowledgement(1, c.attemptId(), commandId, saved.access().stateVersion());
    }

    private static NavigationException safe(RuntimeException failure) {
        if (failure instanceof NavigationException known) return new NavigationException(known.reason());
        return new NavigationException(Reason.valueOf(AttemptCommandFailures.translate(failure).reason().name()));
    }
}
