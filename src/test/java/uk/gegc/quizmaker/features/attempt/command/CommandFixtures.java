package uk.gegc.quizmaker.features.attempt.command;

import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.CommandIdentity;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;

import java.util.List;
import java.util.UUID;

final class CommandFixtures {
    static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final long STATE_VERSION = 7;

    private CommandFixtures() {}

    static Metadata fresh() { return new Metadata(STATE_VERSION, OPERATION, CommandIdentity.FRESH); }

    static ResolvedAttemptPolicy policy(boolean requireAll, AnswerOrder order) {
        var p = BuiltInPracticePreset.V1.bindContent(new ContentPolicy(FIRST, 1,
                FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED));
        var submission = p.submission();
        return new ResolvedAttemptPolicy(p.schemaVersion(), p.content(), p.questionSelection(),
                p.questionOrdering(), new AnswerOrderingPolicy(order, 1), p.delivery(), p.navigation(),
                new SubmissionPolicy(false, false, requireAll, true, submission.mode(), submission.retries(),
                        submission.finalSubmitPayload(), submission.unanswered()),
                p.answerChange(), p.disclosure(), p.hints(), p.timing(), p.pause(), p.attempts(), p.access(),
                p.grading(), p.review(), p.results(), p.accessibility(), p.integrity());
    }

    static Context context(Execution execution, AnswerCollection answers) {
        return context(execution, answers, false, AnswerOrder.AUTHORED, ResponseState.EMPTY, true);
    }

    static Context context(Execution execution, AnswerCollection answers, boolean requireAll,
                           AnswerOrder order, ResponseState target, boolean confirmed) {
        var facts = new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, requireAll, confirmed,
                List.of(new Item(FIRST, answers == AnswerCollection.NOT_STARTED ? ResponseState.EMPTY
                        : ResponseState.ACCEPTED, Scope.APPLICABLE), new Item(TARGET, target, Scope.APPLICABLE)));
        return new Context(Access.VERIFIED, policy(requireAll, order), new State(1, execution, answers),
                STATE_VERSION, new ItemFacts(true, true, target), facts);
    }

    static Context withItem(Context c, ItemFacts item) {
        return new Context(c.access(), c.policy(), c.state(), c.stateVersion(), item, c.completion());
    }

    static Context withCompletion(Context c, CompletionFacts completion) {
        return new Context(c.access(), c.policy(), c.state(), c.stateVersion(), c.item(), completion);
    }

    static Context active() { return context(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS); }
}
