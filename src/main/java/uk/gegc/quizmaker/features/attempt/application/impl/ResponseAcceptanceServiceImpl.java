package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.require;

@Service
public class ResponseAcceptanceServiceImpl implements ResponseAcceptanceService {
    private final AuthenticatedSubjectResolver identity;
    private final AttemptAccessService access;
    private final AttemptReadFactsService facts;
    private final AttemptLifecycleService lifecycle;
    private final AcceptedResponseRepository answers;
    private final CommandReceiptService receipts;
    private final ExecutionTraceService trace;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ResponseAcceptanceServiceImpl(AuthenticatedSubjectResolver identity, AttemptAccessService access,
            AttemptReadFactsService facts, AttemptLifecycleService lifecycle, AcceptedResponseRepository answers,
            CommandReceiptService receipts, ExecutionTraceService trace, Clock clock, MeterRegistry metrics) {
        this.identity = identity; this.access = access; this.facts = facts; this.lifecycle = lifecycle;
        this.answers = answers; this.receipts = receipts; this.trace = trace; this.clock = clock; this.metrics = metrics;
    }

    @Override public Result accept(Command c) {
        Timer.Sample timer = Timer.start(); String outcome = "unavailable";
        try {
            require(c != null && c.attemptId() != null && c.formItemId() != null && c.expectedStateVersion() >= 0
                    && c.key() != null, INVALID_INPUT);
            var subject = identity.resolve(c.attemptId());
            var stable = StableResponseNormalizer.canonical(c.type(), c.response());
            var input = stable.deepCopy().put("type", c.type().name());
            var request = new CommandReceipt.Request(1, subject.userId(), c.attemptId(), AttemptCommands.Type.SUBMIT_ANSWER,
                    c.formItemId(), c.expectedStateVersion(), c.key(), CanonicalCommandInput.fingerprint(1, input));
            class Invocation {
                ResponseAcceptanceException failure;
                <T> T checked(Supplier<T> work) {
                    try { return work.get(); }
                    catch (RuntimeException rejected) { failure = AttemptCommandFailures.translate(rejected); throw failure; }
                }
            }
            var call = new Invocation();
            CommandReceipt.Result result;
            try {
                result = receipts.execute(request, () -> call.checked(() -> {
                    require(answers.lockOwnBinding(subject.userId(), c.attemptId()), NOT_FOUND);
                    // Serialize before shared access/content reads; no shared-to-exclusive upgrade race.
                    lifecycle.lock(c.attemptId());
                    return access.requireAccess(c.attemptId(), AttemptAccessService.Operation.ANSWER);
                }), id -> call.checked(() -> acceptFresh(c, stable, id)));
            } catch (ReceiptException failure) {
                if (call.failure != null && (failure.reason() == ReceiptException.Reason.ACCESS_DENIED
                        || failure.reason() == ReceiptException.Reason.COMMAND_DENIED)) throw call.failure;
                throw failure;
            }
            var ack = result.receipt().acknowledgement();
            outcome = result.replayed() ? "replayed" : "accepted";
            return new Result(ack.attemptId(), ack.resultId(), ack.stateVersion(), result.replayed());
        } catch (RuntimeException failure) {
            var safe = AttemptCommandFailures.translate(failure); outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { timer.stop(Timer.builder("quizmaker.attempt.response").tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* optional telemetry does not change command authority */ }
        }
    }

    private CommandReceipt.Acknowledgement acceptFresh(Command c, ObjectNode stable, UUID commandId) {
        var saved = facts.read(c.attemptId(), AttemptReadFactsService.Purpose.ACCEPT_RESPONSE);
        // ALL_AT_ONCE exposes this complete fixed form; no per-item delivery cursor is invented.
        ParticipantFormProjector.project(saved.form(), saved.revision(), saved.order(), saved.contracts());
        var item = saved.form().items().stream().filter(i -> i.id().equals(c.formItemId())).findFirst()
                .orElseThrow(() -> new ResponseAcceptanceException(NOT_FOUND));
        require(saved.navigation().answerable(item.id()), CONFLICT);
        var question = saved.revision().items().stream().filter(i -> i.id().equals(item.sourceItemId()))
                .findFirst().orElseThrow(() -> new ResponseAcceptanceException(CORRUPT)).question();
        require(question.type() == c.type(), INVALID_INPUT);
        var contracts = saved.contracts().get(question.id());
        require(contracts != null, CORRUPT);
        var current = saved.access().lifecycle();
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), current.state(), current.rowVersion(),
                new AttemptCommands.ItemFacts(true, true, answers.exists(c.attemptId(), item.id())
                        ? CompletionFacts.ResponseState.ACCEPTED : CompletionFacts.ResponseState.EMPTY, item.id(), saved.navigation()), null);
        var decision = AttemptCommands.evaluate(new AttemptCommands.Command(1, AttemptCommands.Type.SUBMIT_ANSWER), context,
                new AttemptCommands.Metadata(c.expectedStateVersion(), commandId, AttemptLifecycle.CommandIdentity.FRESH));
        require(decision.outcome() != AttemptCommands.Outcome.STALE, STALE);
        require(decision.outcome() == AttemptCommands.Outcome.ALLOWED, CONFLICT);
        var normalized = StableResponseNormalizer.bind(question, contracts, stable);
        UUID id = UUID.randomUUID();
        answers.insert(new AcceptedResponseRepository.Value(id, c.attemptId(), saved.form().id(), item.id(), question.id(),
                contracts.response().version(), normalized.toString(), commandId, CommandReceipt.time(clock.instant())));
        var transition = AttemptLifecycle.evaluate(current.state(), AttemptLifecycle.Event.RESPONSE_RECORDED, AttemptLifecycle.Context.fresh());
        require(transition instanceof AttemptLifecycle.Advance, CONFLICT);
        var next = lifecycle.save(c.attemptId(), current.rowVersion(), ((AttemptLifecycle.Advance) transition).state());
        trace.append(new ExecutionTrace.Scope(c.attemptId(), saved.access().policySnapshotId(), saved.form().id()),
                new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.ANSWER_SUBMITTED, ExecutionTrace.Actor.PARTICIPANT,
                        new ExecutionTrace.Data(commandId, item.id(), current.state(), next.state(), ExecutionTrace.ReasonCode.ACCEPTED)));
        return new CommandReceipt.Acknowledgement(1, c.attemptId(), id, next.rowVersion());
    }
}
