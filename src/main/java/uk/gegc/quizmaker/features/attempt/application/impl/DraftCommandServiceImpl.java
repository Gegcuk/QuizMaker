package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
import static uk.gegc.quizmaker.features.attempt.domain.model.draft.DraftCommandException.*;

@Service
public class DraftCommandServiceImpl implements DraftCommandService {
    private final AuthenticatedSubjectResolver identity;
    private final AttemptAccessService access;
    private final AttemptReadFactsService facts;
    private final AttemptLifecycleService lifecycle;
    private final AnswerDraftRepository binding;
    private final AnswerDraftStore drafts;
    private final CommandReceiptService receipts;
    private final DraftCommandResultRepository results;
    private final ExecutionTraceService trace;
    private final TransactionTemplate read;
    private final MeterRegistry metrics;

    public DraftCommandServiceImpl(AuthenticatedSubjectResolver identity, AttemptAccessService access,
            AttemptReadFactsService facts, AttemptLifecycleService lifecycle, AnswerDraftRepository binding,
            AnswerDraftStore drafts, CommandReceiptService receipts, DraftCommandResultRepository results,
            ExecutionTraceService trace, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.identity = identity; this.access = access; this.facts = facts; this.lifecycle = lifecycle;
        this.binding = binding; this.drafts = drafts; this.receipts = receipts; this.results = results;
        this.trace = trace; this.read = new TransactionTemplate(manager); this.read.setReadOnly(true); this.metrics = metrics;
    }

    @Override public Result execute(Command c) {
        String outcome = "unavailable";
        try {
            require(c != null && (c.operation() == AttemptCommands.Type.SAVE_DRAFT || c.operation() == AttemptCommands.Type.DELETE_DRAFT)
                    && c.attemptId() != null && c.formItemId() != null && c.expectedStateVersion() >= 0
                    && c.expectedDraftVersion() >= 0 && c.expectedDraftVersion() < Long.MAX_VALUE && c.key() != null, Reason.INVALID_INPUT);
            boolean delete = c.operation() == AttemptCommands.Type.DELETE_DRAFT;
            require(!delete || c.type() == null && c.response() == null, Reason.INVALID_INPUT);
            var subject = identity.resolve(c.attemptId());
            var stable = delete ? null : StableResponseNormalizer.canonical(c.type(), c.response());
            var input = JsonNodeFactory.instance.objectNode().put("expectedDraftVersion", c.expectedDraftVersion());
            if (!delete) input.put("type", c.type().name()).set("response", stable);
            var request = new CommandReceipt.Request(1, subject.userId(), c.attemptId(), c.operation(), c.formItemId(),
                    c.expectedStateVersion(), c.key(), CanonicalCommandInput.fingerprint(1, input));
            class Invocation {
                DraftCommandException failure;
                <T> T checked(Supplier<T> work) {
                    try { return work.get(); }
                    catch (RuntimeException rejected) { failure = translate(rejected); throw failure; }
                }
            }
            var call = new Invocation();
            CommandReceipt.Result result;
            try {
                result = receipts.execute(request, () -> call.checked(() -> {
                    require(binding.lockSubject(subject.userId(), c.attemptId()), Reason.NOT_FOUND);
                    // Same root-before-content order as acceptance/submission; kept through receipt commit.
                    lifecycle.lock(c.attemptId());
                    return access.requireAccess(c.attemptId(), AttemptAccessService.Operation.ANSWER);
                }), id -> call.checked(() -> mutate(c, subject.userId(), stable, id)));
            } catch (ReceiptException failure) {
                if (call.failure != null && (failure.reason() == ReceiptException.Reason.ACCESS_DENIED
                        || failure.reason() == ReceiptException.Reason.COMMAND_DENIED)) throw call.failure;
                throw failure;
            }
            var ack = result.receipt().acknowledgement();
            // Immutable extension, committed with the draft/receipt. A failed read is safely retried
            // with the same key, including after process restart or a later draft update.
            var saved = read.execute(status -> call.checked(() -> results.read(ack.resultId())
                    .orElseThrow(() -> new DraftCommandException(Reason.CORRUPT))));
            require(saved.commandId().equals(result.receipt().id()) && saved.attemptId().equals(c.attemptId())
                    && saved.formItemId().equals(c.formItemId()) && ack.attemptId().equals(c.attemptId())
                    && ack.stateVersion() == c.expectedStateVersion() && saved.draftVersion() == c.expectedDraftVersion() + 1
                    && saved.status() == (delete ? AnswerDraft.Status.DELETED : AnswerDraft.Status.PRESENT), Reason.CORRUPT);
            outcome = result.replayed() ? "replayed" : delete ? "deleted" : "saved";
            return new Result(saved.attemptId(), saved.formItemId(), ack.stateVersion(), saved.draftVersion(), saved.status(), result.replayed());
        } catch (RuntimeException failure) {
            var safe = translate(failure); outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { metrics.counter("quizmaker.attempt.draft.command", "version", "1", "operation",
                    c != null && c.operation() == AttemptCommands.Type.DELETE_DRAFT ? "delete" : "save", "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* optional telemetry never changes command authority */ }
        }
    }

    private CommandReceipt.Acknowledgement mutate(Command c, UUID user, ObjectNode stable, UUID commandId) {
        var saved = facts.read(c.attemptId(), AttemptReadFactsService.Purpose.DRAFT_COMMAND);
        ParticipantFormProjector.project(saved.form(), saved.revision(), saved.order(), saved.contracts());
        var item = saved.form().items().stream().filter(i -> i.id().equals(c.formItemId())).findFirst()
                .orElseThrow(() -> new DraftCommandException(Reason.NOT_FOUND));
        require(saved.navigation().answerable(item.id()), Reason.CONFLICT);
        var question = saved.revision().items().stream().filter(i -> i.id().equals(item.sourceItemId())).findFirst()
                .orElseThrow(() -> new DraftCommandException(Reason.CORRUPT)).question();
        var contract = saved.contracts().get(question.id());
        require(contract != null, Reason.CORRUPT);
        var ref = new AnswerDraft.Reference(c.attemptId(), saved.form().id(), item.id(), question.id(), contract.response().version());
        var state = saved.access().lifecycle();
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), state.state(), state.rowVersion(),
                new AttemptCommands.ItemFacts(true, true, binding.accepted(ref)
                        ? CompletionFacts.ResponseState.ACCEPTED : CompletionFacts.ResponseState.EMPTY, item.id(), saved.navigation()), null);
        var decision = AttemptCommands.evaluate(new AttemptCommands.Command(1, c.operation()), context,
                new AttemptCommands.Metadata(c.expectedStateVersion(), commandId, AttemptLifecycle.CommandIdentity.FRESH));
        require(decision.outcome() != AttemptCommands.Outcome.STALE, Reason.STALE);
        require(decision.outcome() == AttemptCommands.Outcome.ALLOWED, Reason.INACTIVE);
        boolean delete = c.operation() == AttemptCommands.Type.DELETE_DRAFT;
        if (!delete) require(question.type() == c.type(), Reason.INVALID_INPUT);
        var draft = delete ? drafts.delete(user, ref, c.expectedDraftVersion())
                : drafts.save(user, ref, c.expectedDraftVersion(), StableResponseNormalizer.bind(question, contract, stable).toString());
        results.insert(new DraftCommandResult(commandId, c.attemptId(), item.id(), draft.version(), draft.status()));
        trace.append(new ExecutionTrace.Scope(c.attemptId(), saved.access().policySnapshotId(), saved.form().id()),
                new ExecutionTrace.Event(1, UUID.randomUUID(), delete ? ExecutionTrace.Type.DRAFT_DELETED : ExecutionTrace.Type.DRAFT_SAVED,
                        ExecutionTrace.Actor.PARTICIPANT, new ExecutionTrace.Data(commandId, item.id(), null, null,
                        delete ? ExecutionTrace.ReasonCode.DELETED : ExecutionTrace.ReasonCode.SAVED)));
        return new CommandReceipt.Acknowledgement(1, c.attemptId(), commandId, state.rowVersion());
    }

    private static DraftCommandException translate(RuntimeException failure) {
        if (failure instanceof DraftCommandException known) return new DraftCommandException(known.reason());
        if (failure instanceof AnswerDraftException draft) return new DraftCommandException(switch (draft.reason()) {
            case INVALID_INPUT -> Reason.INVALID_INPUT; case NOT_FOUND -> Reason.NOT_FOUND; case CONFLICT -> Reason.CONFLICT;
            case INACTIVE -> Reason.INACTIVE; case UNSUPPORTED -> Reason.UNSUPPORTED; case CORRUPT -> Reason.CORRUPT;
            case RETRYABLE -> Reason.RETRYABLE; case BUSY -> Reason.IN_PROGRESS; default -> Reason.UNAVAILABLE;
        });
        // Reuse the established bounded classification of shared access/receipt/response contracts.
        return new DraftCommandException(Reason.valueOf(AttemptCommandFailures.translate(failure).reason().name()));
    }
}
