package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.require;

/** Shared acceptance steps. Both commands validate through the same rules and perform the same writes. */
final class AcceptedResponseWork {
    private AcceptedResponseWork() { }
    record Prepared(UUID itemId, UUID questionId, ContractVersion contract, String normalized) {
        @Override public String toString() { return "PreparedResponse[redacted]"; }
    }
    record Written(CommandReceipt.Acknowledgement acknowledgement, LifecycleState state) { }
    private record BoundQuestion(QuestionRevision question, ResolvedQuestionContracts contracts) { }

    static Prepared prepare(AttemptReadFactsService.Facts saved, UUID itemId, QuestionType type, ObjectNode stable,
                            long expectedVersion, UUID commandId, java.util.function.BooleanSupplier accepted) {
        var bound = question(saved, itemId, type);
        requireAnswerAllowed(saved, itemId, expectedVersion, commandId, accepted.getAsBoolean());
        return normalize(itemId, bound, stable);
    }

    static Prepared normalize(AttemptReadFactsService.Facts saved, UUID itemId, QuestionType type, ObjectNode stable) {
        return normalize(itemId, question(saved, itemId, type), stable);
    }

    private static BoundQuestion question(AttemptReadFactsService.Facts saved, UUID itemId, QuestionType type) {
        var item = saved.form().items().stream().filter(i -> i.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new ResponseAcceptanceException(NOT_FOUND));
        require(saved.current(item.id()), NOT_FOUND);
        require(saved.navigation().answerable(item.id()), CONFLICT);
        var question = saved.revision().items().stream().filter(i -> i.id().equals(item.sourceItemId()))
                .findFirst().orElseThrow(() -> new ResponseAcceptanceException(CORRUPT)).question();
        require(question.type() == type, INVALID_INPUT);
        var contracts = saved.contracts().get(question.id()); require(contracts != null, CORRUPT);
        return new BoundQuestion(question, contracts);
    }

    static void requireAnswerAllowed(AttemptReadFactsService.Facts saved, UUID itemId, long expectedVersion,
                                     UUID commandId, boolean accepted) {
        var current = saved.access().lifecycle();
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), current.state(), current.rowVersion(),
                new AttemptCommands.ItemFacts(true, true, accepted ? CompletionFacts.ResponseState.ACCEPTED : CompletionFacts.ResponseState.EMPTY,
                        itemId, saved.navigation()), null);
        var decision = AttemptCommands.evaluate(new AttemptCommands.Command(1, AttemptCommands.Type.SUBMIT_ANSWER), context,
                new AttemptCommands.Metadata(expectedVersion, commandId, AttemptLifecycle.CommandIdentity.FRESH));
        require(decision.outcome() != AttemptCommands.Outcome.STALE, STALE);
        require(decision.outcome() == AttemptCommands.Outcome.ALLOWED, CONFLICT);
    }

    private static Prepared normalize(UUID itemId, BoundQuestion bound, ObjectNode stable) {
        var normalized = StableResponseNormalizer.bind(bound.question(), bound.contracts(), stable);
        return new Prepared(itemId, bound.question().id(), bound.contracts().response().version(), normalized.toString());
    }

    static Written persist(Prepared prepared, AttemptReadFactsService.Facts saved,
            LifecycleState current, UUID commandId, AcceptedResponseRepository answers,
            AttemptLifecycleService lifecycle, ExecutionTraceService trace, Clock clock) {
        UUID id = UUID.randomUUID();
        answers.insert(new AcceptedResponseRepository.Value(id, saved.access().attemptId(), saved.form().id(), prepared.itemId(),
                prepared.questionId(), prepared.contract(), prepared.normalized(), commandId, CommandReceipt.time(clock.instant())));
        var transition = AttemptLifecycle.evaluate(current.state(), AttemptLifecycle.Event.RESPONSE_RECORDED, AttemptLifecycle.Context.fresh());
        require(transition instanceof AttemptLifecycle.Advance, CONFLICT);
        var next = lifecycle.save(saved.access().attemptId(), current.rowVersion(), ((AttemptLifecycle.Advance) transition).state());
        trace.append(new ExecutionTrace.Scope(saved.access().attemptId(), saved.access().policySnapshotId(), saved.form().id()),
                new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.ANSWER_SUBMITTED, ExecutionTrace.Actor.PARTICIPANT,
                        new ExecutionTrace.Data(commandId, prepared.itemId(), current.state(), next.state(), ExecutionTrace.ReasonCode.ACCEPTED)));
        return new Written(new CommandReceipt.Acknowledgement(1, saved.access().attemptId(), id, next.rowVersion()), next);
    }
}
