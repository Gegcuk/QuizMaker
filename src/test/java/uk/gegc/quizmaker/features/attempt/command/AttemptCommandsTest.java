package uk.gegc.quizmaker.features.attempt.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.CommandIdentity;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.command.CommandFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.evaluate;

class AttemptCommandsTest {
    // COMMAND-TABLE: an explicit oracle, not a second invocation of the production lifecycle table.
    @ParameterizedTest
    @CsvSource({
            "CREATED, NOT_STARTED, START, ALLOWED, -",
            "CREATED, NOT_STARTED, VIEW_ATTEMPT, LIFECYCLE_DENIED, NOT_STARTED",
            "CREATED, NOT_STARTED, NAVIGATE_TO_ITEM, LIFECYCLE_DENIED, NOT_STARTED",
            "CREATED, NOT_STARTED, SUBMIT_ANSWER, LIFECYCLE_DENIED, NOT_STARTED",
            "CREATED, NOT_STARTED, SUBMIT_ATTEMPT, LIFECYCLE_DENIED, NOT_STARTED",
            "IN_PROGRESS, NOT_STARTED, START, LIFECYCLE_DENIED, ALREADY_STARTED",
            "IN_PROGRESS, NOT_STARTED, VIEW_ATTEMPT, ALLOWED, -",
            "IN_PROGRESS, NOT_STARTED, NAVIGATE_TO_ITEM, ALLOWED, -",
            "IN_PROGRESS, NOT_STARTED, SUBMIT_ANSWER, ALLOWED, -",
            "IN_PROGRESS, NOT_STARTED, SUBMIT_ATTEMPT, ALLOWED, -",
            "IN_PROGRESS, IN_PROGRESS, START, LIFECYCLE_DENIED, ALREADY_STARTED",
            "IN_PROGRESS, IN_PROGRESS, VIEW_ATTEMPT, ALLOWED, -",
            "IN_PROGRESS, IN_PROGRESS, NAVIGATE_TO_ITEM, ALLOWED, -",
            "IN_PROGRESS, IN_PROGRESS, SUBMIT_ANSWER, ALLOWED, -",
            "IN_PROGRESS, IN_PROGRESS, SUBMIT_ATTEMPT, ALLOWED, -",
            "SUBMITTED, COMPLETE, START, LIFECYCLE_DENIED, TERMINAL_STATE",
            "SUBMITTED, COMPLETE, VIEW_ATTEMPT, ALLOWED, -",
            "SUBMITTED, COMPLETE, NAVIGATE_TO_ITEM, LIFECYCLE_DENIED, TERMINAL_STATE",
            "SUBMITTED, COMPLETE, SUBMIT_ANSWER, LIFECYCLE_DENIED, TERMINAL_STATE",
            "SUBMITTED, COMPLETE, SUBMIT_ATTEMPT, LIFECYCLE_DENIED, TERMINAL_STATE"
    })
    void eachFreshTupleHasExactlyTheSpecifiedDecision(Execution execution, AnswerCollection answers,
                                                      Type type, Reason reason, String lifecycleReason) {
        var c = context(execution, answers);
        assertDecision(evaluate(new Command(1, type), c, fresh()), reason, lifecycleReason);
        assertThat(c).isEqualTo(context(execution, answers));
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, NOT_STARTED, START, false",
            "CREATED, NOT_STARTED, NAVIGATE_TO_ITEM, false",
            "CREATED, NOT_STARTED, SUBMIT_ANSWER, false",
            "CREATED, NOT_STARTED, SUBMIT_ATTEMPT, false",
            "IN_PROGRESS, NOT_STARTED, START, true",
            "IN_PROGRESS, NOT_STARTED, NAVIGATE_TO_ITEM, true",
            "IN_PROGRESS, NOT_STARTED, SUBMIT_ANSWER, false",
            "IN_PROGRESS, NOT_STARTED, SUBMIT_ATTEMPT, false",
            "IN_PROGRESS, IN_PROGRESS, START, true",
            "IN_PROGRESS, IN_PROGRESS, NAVIGATE_TO_ITEM, true",
            "IN_PROGRESS, IN_PROGRESS, SUBMIT_ANSWER, true",
            "IN_PROGRESS, IN_PROGRESS, SUBMIT_ATTEMPT, false",
            "SUBMITTED, COMPLETE, START, true",
            "SUBMITTED, COMPLETE, NAVIGATE_TO_ITEM, true",
            "SUBMITTED, COMPLETE, SUBMIT_ANSWER, true",
            "SUBMITTED, COMPLETE, SUBMIT_ATTEMPT, true"
    })
    void verifiedReplayDoesNotRepeatEligibilityOrRequireCurrentVersion(Execution execution,
            AnswerCollection answers, Type type, boolean replay) {
        // No payload/form completion is reevaluated. A real #599 consumer must prove receipt identity.
        var c = withCompletion(context(execution, answers), null);
        var result = evaluate(new Command(1, type), c,
                new Metadata(0, OPERATION, CommandIdentity.VERIFIED_IDENTICAL));
        assertDecision(result, replay ? Reason.VERIFIED_REPLAY : Reason.LIFECYCLE_DENIED,
                replay ? "-" : "REPLAY_STATE_MISMATCH");
    }

    @ParameterizedTest
    @EnumSource(value = Type.class, names = "VIEW_ATTEMPT", mode = EnumSource.Mode.EXCLUDE)
    void changedIdentityIsConflictAndStaleFreshCommandsHaveNoPermission(Type type) {
        var command = new Command(1, type);
        assertDecision(evaluate(command, active(), new Metadata(0, OPERATION, CommandIdentity.CONFLICTING)),
                Reason.CONFLICTING_REPLAY, "-");
        for (long version : new long[]{0, STATE_VERSION - 1, STATE_VERSION + 1, Long.MAX_VALUE}) {
            assertDecision(evaluate(command, active(), new Metadata(version, OPERATION, CommandIdentity.FRESH)),
                    Reason.STALE_VERSION, "-");
        }
    }

    @Test
    void visitingAnAcceptedAnswerDoesNotUnlockItOrTurnNewKeysIntoReplays() {
        var c = context(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS, false,
                AnswerOrder.AUTHORED, ResponseState.ACCEPTED, true);
        assertDecision(evaluate(new Command(1, Type.NAVIGATE_TO_ITEM), c, fresh()), Reason.ALLOWED, "-");
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ANSWER), c, fresh()), Reason.ANSWER_LOCKED, "-");
        assertThat(AttemptCommands.allowedActions(c).actions())
                .containsExactly(Type.VIEW_ATTEMPT, Type.NAVIGATE_TO_ITEM, Type.SUBMIT_ATTEMPT);
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ANSWER), c,
                new Metadata(STATE_VERSION - 1, OPERATION, CommandIdentity.VERIFIED_IDENTICAL)),
                Reason.VERIFIED_REPLAY, "-");
    }

    @ParameterizedTest
    @EnumSource(value = Type.class, names = {"NAVIGATE_TO_ITEM", "SUBMIT_ANSWER"})
    void missingForeignOrUndeliveredItemDoesNotGrantItemActions(Type type) {
        var c = active();
        for (ItemFacts item : new ItemFacts[]{null, new ItemFacts(false, true, ResponseState.EMPTY),
                new ItemFacts(true, false, ResponseState.EMPTY), new ItemFacts(true, true, null)}) {
            assertDecision(evaluate(new Command(1, type), withItem(c, item), fresh()), Reason.ITEM_UNAVAILABLE, "-");
        }
        assertDecision(evaluate(new Command(1, type), c, fresh()), Reason.ALLOWED, "-");
    }

    @ParameterizedTest
    @EnumSource(value = ResponseState.class, names = {"DRAFT", "EDITABLE", "PENDING_CONFIRMATION"})
    void deferredResponseStatesCannotEnableAnotherPolicy(ResponseState response) {
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ANSWER),
                withItem(active(), new ItemFacts(true, true, response)), fresh()), Reason.UNSUPPORTED_RESPONSE_STATE, "-");
    }

    @Test
    void acceptedItemCannotBeCombinedWithUnstartedCollection() {
        var c = withItem(context(Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED),
                new ItemFacts(true, true, ResponseState.ACCEPTED));
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ANSWER), c, fresh()),
                Reason.LIFECYCLE_DENIED, "CONTRADICTORY_FACTS");
    }

    @Test
    void omittedAnswersRequireTheCapturedPolicyAndExplicitConfirmation() {
        for (boolean requireAll : List.of(false, true)) {
            var c = context(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS, requireAll,
                    AnswerOrder.AUTHORED, ResponseState.EMPTY, true);
            assertDecision(evaluate(new Command(1, Type.SUBMIT_ATTEMPT), c, fresh()),
                    requireAll ? Reason.LIFECYCLE_DENIED : Reason.ALLOWED,
                    requireAll ? "INCOMPLETE_REQUIRED_SCOPE" : "-");
            var allAccepted = context(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS, requireAll,
                    AnswerOrder.AUTHORED, ResponseState.ACCEPTED, true);
            assertDecision(evaluate(new Command(1, Type.SUBMIT_ATTEMPT), allAccepted, fresh()), Reason.ALLOWED, "-");
            var unconfirmed = context(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS, requireAll,
                    AnswerOrder.AUTHORED, ResponseState.ACCEPTED, false);
            assertDecision(evaluate(new Command(1, Type.SUBMIT_ATTEMPT), unconfirmed, fresh()),
                    Reason.LIFECYCLE_DENIED, "CONFIRMATION_REQUIRED");
            assertThat(c.completion().items()).extracting(Item::response)
                    .containsExactly(ResponseState.ACCEPTED, ResponseState.EMPTY);
        }
    }

    @Test
    void completionFactsCannotWeakenRequireAllOrEnableEditableFinalization() {
        var c = context(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS, true,
                AnswerOrder.AUTHORED, ResponseState.EMPTY, true);
        var weaker = active().completion();
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ATTEMPT), withCompletion(c, weaker), fresh()),
                Reason.POLICY_FACTS_MISMATCH, "-");
        var editable = new CompletionFacts(1, FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION, true, true,
                List.of(new Item(FIRST, ResponseState.EDITABLE, Scope.APPLICABLE)));
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ATTEMPT), withCompletion(c, editable), fresh()),
                Reason.POLICY_FACTS_MISMATCH, "-");
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ATTEMPT), withCompletion(c, null), fresh()),
                Reason.LIFECYCLE_DENIED, "MISSING_CONTEXT");
    }

    static Stream<Context> supportedContexts() {
        var contexts = new java.util.ArrayList<Context>();
        for (var tuple : List.of(new State(1, Execution.CREATED, AnswerCollection.NOT_STARTED),
                new State(1, Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED),
                new State(1, Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS),
                new State(1, Execution.SUBMITTED, AnswerCollection.COMPLETE))) {
            for (boolean requireAll : List.of(false, true)) for (var order : AnswerOrder.values()) {
                for (boolean confirmed : List.of(false, true)) {
                    contexts.add(context(tuple.execution(), tuple.answers(), requireAll, order, ResponseState.EMPTY, confirmed));
                    if (tuple.answers() != AnswerCollection.NOT_STARTED) {
                        contexts.add(context(tuple.execution(), tuple.answers(), requireAll, order, ResponseState.ACCEPTED, confirmed));
                    }
                }
            }
        }
        return contexts.stream();
    }

    @ParameterizedTest
    @MethodSource("supportedContexts")
    void projectionAgreesWithFreshDecisionsAndIsStableImmutableAndAdvisory(Context c) {
        var actions = AttemptCommands.allowedActions(c);
        assertThat(AttemptCommands.allowedActions(c)).isEqualTo(actions);
        assertThat(actions.actions()).isSortedAccordingTo(java.util.Comparator.comparingInt(Enum::ordinal));
        for (Type type : Type.values()) {
            assertThat(actions.contains(type)).as(type.name())
                    .isEqualTo(evaluate(new Command(1, type), c, fresh()).outcome() == Outcome.ALLOWED);
        }
        assertThatThrownBy(() -> actions.actions().clear()).isInstanceOf(UnsupportedOperationException.class);
        // Keeping an old allowed-action projection cannot authorize a stale command.
        var newer = new Context(c.access(), c.policy(), c.state(), STATE_VERSION + 1, c.item(), c.completion());
        for (Type type : actions.actions()) if (type != Type.VIEW_ATTEMPT) {
            assertDecision(evaluate(new Command(1, type), newer, fresh()), Reason.STALE_VERSION, "-");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unsupportedCommandVersionDoesNotSelectCurrentDefaults(int version) {
        assertDecision(evaluate(new Command(version, Type.SUBMIT_ANSWER), active(), fresh()),
                Reason.UNSUPPORTED_VERSION, "-");
    }

    @Test
    void missingOrInvalidCommandContextAndMetadataFailClosed() {
        assertDecision(evaluate(null, active(), fresh()), Reason.INVALID_INPUT, "-");
        assertDecision(evaluate(new Command(1, null), active(), fresh()), Reason.INVALID_INPUT, "-");
        assertDecision(evaluate(new Command(1, Type.START), null, fresh()), Reason.MISSING_CONTEXT, "-");
        var c = active();
        assertDecision(evaluate(new Command(1, Type.START), new Context(Access.VERIFIED, null, c.state(), 7, null, null), fresh()),
                Reason.MISSING_CONTEXT, "-");
        assertDecision(evaluate(new Command(1, Type.START), new Context(Access.VERIFIED, c.policy(), null, 7, null, null), fresh()),
                Reason.MISSING_CONTEXT, "-");
        assertDecision(evaluate(new Command(1, Type.START), new Context(Access.VERIFIED, c.policy(), c.state(), -1, null, null), fresh()),
                Reason.INVALID_INPUT, "-");
        assertDecision(evaluate(new Command(1, Type.SUBMIT_ANSWER), c, null), Reason.MISSING_METADATA, "-");
        for (Metadata metadata : List.of(new Metadata(-1, OPERATION, CommandIdentity.FRESH),
                new Metadata(7, null, CommandIdentity.FRESH), new Metadata(7, OPERATION, null))) {
            assertDecision(evaluate(new Command(1, Type.SUBMIT_ANSWER), c, metadata), Reason.INVALID_METADATA, "-");
        }
        assertDecision(evaluate(new Command(1, Type.VIEW_ATTEMPT), c, null), Reason.ALLOWED, "-");
    }

    static void assertDecision(Decision actual, Reason reason, String lifecycleReason) {
        assertThat(actual.reason()).isEqualTo(reason);
        assertThat(actual.lifecycleReason()).isEqualTo(lifecycleReason.equals("-") ? null
                : LifecycleException.Reason.valueOf(lifecycleReason));
        assertThat(actual.acknowledgement().outcome()).isEqualTo(actual.outcome());
    }
}
