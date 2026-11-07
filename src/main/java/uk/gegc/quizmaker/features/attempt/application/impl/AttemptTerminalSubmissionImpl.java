package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubmissionRepository;
import static uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException.Reason.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AttemptTerminalSubmissionImpl implements AttemptTerminalSubmission {
    private final AttemptLifecycleService lifecycle;
    private final SubmissionResponseFinalizer finalizer;
    private final ExecutionTraceService trace;
    private final AttemptSubmissionRepository submissions;
    public AttemptTerminalSubmissionImpl(AttemptLifecycleService lifecycle, SubmissionResponseFinalizer finalizer,
            ExecutionTraceService trace, AttemptSubmissionRepository submissions) {
        this.lifecycle = lifecycle; this.finalizer = finalizer; this.trace = trace; this.submissions = submissions;
    }
    @Override public CommandReceipt.Acknowledgement finish(AttemptReadFactsService.Facts facts, long expectedVersion,
                                                         UUID commandId, CompletionFacts completion) {
        require(facts != null && completion != null && commandId != null, INVALID_INPUT);
        var current = lifecycle.lock(facts.access().attemptId());
        require(current.rowVersion() == expectedVersion && current.equals(facts.access().lifecycle()), STALE);
        require(completion.items().stream().map(CompletionFacts.Item::formItemId).toList()
                .equals(facts.form().items().stream().map(i -> i.id()).toList()), CORRUPT);
        require(completion.requireAllAnswers() == facts.policy().submission().requireAllAnswers(), CORRUPT);
        var decision = AttemptLifecycle.evaluate(current.state(), AttemptLifecycle.Event.SUBMIT,
                AttemptLifecycle.Context.submit(completion));
        if (decision instanceof AttemptLifecycle.Denied denied) throw denial(denied.reason(), completion);
        require(decision instanceof AttemptLifecycle.Advance, CONFLICT);
        var advance = (AttemptLifecycle.Advance) decision;
        // Conversion must complete in this transaction BEFORE the terminal tuple. No proposal
        // is part of finalizeItems; a conversion exception rolls back every authoritative write.
        finalizer.finalizeResponses(facts, advance.plan());
        var next = lifecycle.save(current.attemptId(), current.rowVersion(), advance.state());
        var scope = new ExecutionTrace.Scope(current.attemptId(), facts.access().policySnapshotId(), facts.form().id());
        for (var event : List.of(ExecutionTrace.Type.ANSWER_LOCKED, ExecutionTrace.Type.ATTEMPT_SUBMITTED)) {
            trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), event, ExecutionTrace.Actor.PARTICIPANT,
                    new ExecutionTrace.Data(commandId, null, current.state(), next.state(),
                            event == ExecutionTrace.Type.ANSWER_LOCKED ? ExecutionTrace.ReasonCode.LOCKED : ExecutionTrace.ReasonCode.SUBMITTED)));
        }
        var value = new AttemptSubmissionRepository.Submission(UUID.randomUUID(), current.attemptId(), facts.form().id(),
                facts.access().policySnapshotId(), facts.revision().id(), commandId, next.rowVersion(),
                completion.items().size() - advance.plan().unansweredItems().size(), advance.plan().unansweredItems().size(), next.submittedAt());
        submissions.insert(value);
        submissions.enqueue(value);
        return new CommandReceipt.Acknowledgement(1, current.attemptId(), value.id(), next.rowVersion());
    }
    static AttemptSubmissionException denial(LifecycleException.Reason reason, CompletionFacts completion) {
        return switch (reason) {
            case INCOMPLETE_REQUIRED_SCOPE -> new AttemptSubmissionException(INCOMPLETE, completion.items().stream()
                    .filter(i -> i.scope() == CompletionFacts.Scope.APPLICABLE && i.response() != CompletionFacts.ResponseState.ACCEPTED
                            && i.response() != CompletionFacts.ResponseState.EDITABLE)
                    .map(CompletionFacts.Item::formItemId).toList());
            case CONFIRMATION_REQUIRED -> new AttemptSubmissionException(CONFIRMATION_REQUIRED);
            case TERMINAL_STATE, NOT_STARTED -> new AttemptSubmissionException(INACTIVE);
            default -> new AttemptSubmissionException(CORRUPT);
        };
    }
}
