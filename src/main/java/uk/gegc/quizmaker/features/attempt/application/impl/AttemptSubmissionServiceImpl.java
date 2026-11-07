package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.*;
import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import uk.gegc.quizmaker.features.attempt.infra.receipt.*;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import static uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException.Reason.*;

@Service
public class AttemptSubmissionServiceImpl implements AttemptSubmissionService {
    private final AuthenticatedSubjectResolver identity;
    private final AttemptAccessService access;
    private final AcceptedResponseRepository answers;
    private final AttemptLifecycleService lifecycle;
    private final AttemptReadFactsService facts;
    private final SubmissionResponseFinalizer finalizer;
    private final AttemptTerminalSubmission terminal;
    private final CommandReceiptService receipts;
    private final MeterRegistry metrics;
    public AttemptSubmissionServiceImpl(AuthenticatedSubjectResolver identity, AttemptAccessService access,
            AcceptedResponseRepository answers, AttemptLifecycleService lifecycle, AttemptReadFactsService facts,
            SubmissionResponseFinalizer finalizer, AttemptTerminalSubmission terminal, CommandReceiptService receipts, MeterRegistry metrics) {
        this.identity = identity; this.access = access; this.answers = answers; this.lifecycle = lifecycle;
        this.facts = facts; this.finalizer = finalizer; this.terminal = terminal; this.receipts = receipts; this.metrics = metrics;
    }
    @Override public Result submit(Command command) {
        Timer.Sample sample = null;
        try { sample = Timer.start(metrics); } catch (RuntimeException ignored) { /* optional telemetry */ }
        String outcome = "unavailable";
        try {
            require(command != null && command.attemptId() != null && command.expectedStateVersion() >= 0 && command.key() != null, INVALID_INPUT);
            var subject = identity.resolve(command.attemptId());
            var request = new CommandReceipt.Request(1, subject.userId(), command.attemptId(), AttemptCommands.Type.SUBMIT_ATTEMPT,
                    command.attemptId(), command.expectedStateVersion(), command.key(), CanonicalCommandInput.fingerprint(1,
                    JsonNodeFactory.instance.objectNode().put("completionConfirmed", command.completionConfirmed())));
            class Invocation {
                AttemptSubmissionException failure;
                <T> T checked(java.util.function.Supplier<T> work) {
                    try { return work.get(); }
                    catch (RuntimeException rejected) { failure = translate(rejected); throw failure; }
                }
            }
            var call = new Invocation();
            CommandReceipt.Result result;
            try {
                result = receipts.execute(request, () -> call.checked(() -> {
                    require(answers.lockOwnBinding(subject.userId(), command.attemptId()), NOT_FOUND);
                    lifecycle.lock(command.attemptId());
                    return access.requireAccess(command.attemptId(), AttemptAccessService.Operation.ANSWER);
                }), id -> call.checked(() -> submitFresh(command, id)));
            } catch (ReceiptException rejected) {
                if (call.failure != null && (rejected.reason() == ReceiptException.Reason.ACCESS_DENIED
                        || rejected.reason() == ReceiptException.Reason.COMMAND_DENIED)) throw call.failure;
                throw rejected;
            }
            var ack = result.receipt().acknowledgement();
            outcome = result.replayed() ? "replayed" : "submitted";
            return new Result(ack.attemptId(), ack.resultId(), ack.stateVersion(), result.replayed());
        } catch (RuntimeException failure) {
            var safe = translate(failure); outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { if (sample != null) sample.stop(Timer.builder("quizmaker.attempt.submission")
                    .tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* telemetry cannot alter the transaction outcome */ }
        }
    }
    private CommandReceipt.Acknowledgement submitFresh(Command command, java.util.UUID id) {
        var saved = facts.read(command.attemptId(), AttemptReadFactsService.Purpose.SUBMIT_ATTEMPT);
        ParticipantFormProjector.project(saved.form(), saved.revision(), saved.order(), saved.contracts());
        var completion = finalizer.prepare(saved, command.completionConfirmed());
        var current = saved.access().lifecycle();
        var decision = AttemptCommands.evaluate(new AttemptCommands.Command(1, AttemptCommands.Type.SUBMIT_ATTEMPT),
                new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), current.state(), current.rowVersion(), null, completion),
                new AttemptCommands.Metadata(command.expectedStateVersion(), id, AttemptLifecycle.CommandIdentity.FRESH));
        require(decision.outcome() != AttemptCommands.Outcome.STALE, STALE);
        if (decision.lifecycleReason() != null) throw AttemptTerminalSubmissionImpl.denial(decision.lifecycleReason(), completion);
        require(decision.outcome() == AttemptCommands.Outcome.ALLOWED, UNSUPPORTED);
        return terminal.finish(saved, command.expectedStateVersion(), id, completion);
    }
    private static AttemptSubmissionException translate(RuntimeException failure) {
        if (failure instanceof AttemptSubmissionException known) return new AttemptSubmissionException(known.reason(), known.missing());
        if (failure instanceof SubjectResolutionException identity) return new AttemptSubmissionException(switch (identity.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED; case UNAVAILABLE -> UNAVAILABLE; default -> NOT_FOUND;
        });
        if (failure instanceof AttemptAccessException denied) return new AttemptSubmissionException(switch (denied.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED; case DENIED -> NOT_FOUND; case IN_PROGRESS -> IN_PROGRESS;
            case RETRYABLE -> RETRYABLE; default -> UNAVAILABLE;
        });
        if (failure instanceof AttemptDeliveryException invalid) return new AttemptSubmissionException(switch (invalid.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED; case NOT_FOUND -> NOT_FOUND; case INACTIVE -> INACTIVE;
            case BUSY -> IN_PROGRESS; case UNSUPPORTED -> UNSUPPORTED; case CORRUPT -> CORRUPT; case UNAVAILABLE -> UNAVAILABLE;
        });
        if (failure instanceof ReceiptException receipt) return new AttemptSubmissionException(switch (receipt.reason()) {
            case CONFLICT -> CONFLICT; case IN_PROGRESS -> IN_PROGRESS; case RETRYABLE -> RETRYABLE;
            case EXPIRED -> EXPIRED; case UNKNOWN_OUTCOME -> UNKNOWN_OUTCOME; case ACCESS_DENIED -> NOT_FOUND;
            case INVALID_INPUT -> INVALID_INPUT; case UNSUPPORTED_VERSION -> UNSUPPORTED; default -> UNAVAILABLE;
        });
        if (failure instanceof DataAccessException || failure instanceof TransactionException || failure instanceof DatabaseConflictException)
            return new AttemptSubmissionException(switch (ReceiptDatabaseFailure.reason(failure)) {
                case RETRYABLE -> RETRYABLE; case IN_PROGRESS -> IN_PROGRESS; default -> UNAVAILABLE;
            });
        return new AttemptSubmissionException(UNAVAILABLE);
    }
}
