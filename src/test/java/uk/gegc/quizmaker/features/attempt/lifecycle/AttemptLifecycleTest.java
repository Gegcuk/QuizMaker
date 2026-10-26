package uk.gegc.quizmaker.features.attempt.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.evaluate;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException.Reason.*;

class AttemptLifecycleTest {
    static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // LIFECYCLE-TABLE: explicit specification, including a successful control for each event.
    @ParameterizedTest
    @CsvSource({
            "CREATED, NOT_STARTED, START, IN_PROGRESS, NOT_STARTED, -",
            "CREATED, NOT_STARTED, RESPONSE_RECORDED, -, -, NOT_STARTED",
            "CREATED, NOT_STARTED, SUBMIT, -, -, NOT_STARTED",
            "IN_PROGRESS, NOT_STARTED, START, -, -, ALREADY_STARTED",
            "IN_PROGRESS, NOT_STARTED, RESPONSE_RECORDED, IN_PROGRESS, IN_PROGRESS, -",
            "IN_PROGRESS, NOT_STARTED, SUBMIT, SUBMITTED, COMPLETE, -",
            "IN_PROGRESS, IN_PROGRESS, START, -, -, ALREADY_STARTED",
            "IN_PROGRESS, IN_PROGRESS, RESPONSE_RECORDED, IN_PROGRESS, IN_PROGRESS, -",
            "IN_PROGRESS, IN_PROGRESS, SUBMIT, SUBMITTED, COMPLETE, -",
            "SUBMITTED, COMPLETE, START, -, -, TERMINAL_STATE",
            "SUBMITTED, COMPLETE, RESPONSE_RECORDED, -, -, TERMINAL_STATE",
            "SUBMITTED, COMPLETE, SUBMIT, -, -, TERMINAL_STATE"
    })
    void everyFreshStateEventPairHasOneSpecifiedOutcome(Execution execution, AnswerCollection answers,
                                                       Event event, String nextExecution,
                                                       String nextAnswers, String denial) {
        var input = state(execution, answers);
        var response = answers == AnswerCollection.IN_PROGRESS ? ResponseState.ACCEPTED : ResponseState.EMPTY;
        var context = Context.submit(facts(response));
        var actual = evaluate(input, event, context);
        if (!denial.equals("-")) {
            assertThat(actual).isEqualTo(new Denied(LifecycleException.Reason.valueOf(denial)));
        } else {
            assertThat(actual).isInstanceOfSatisfying(Advance.class, allowed -> {
                assertThat(allowed.state()).isEqualTo(state(Execution.valueOf(nextExecution),
                        AnswerCollection.valueOf(nextAnswers)));
                assertThat(allowed.plan().finalizeItems()).isEmpty();
                assertThat(allowed.plan().unansweredItems()).containsExactlyElementsOf(
                        event == Event.SUBMIT && response == ResponseState.EMPTY ? List.of(ITEM) : List.of());
            });
        }
        assertThat(input).isEqualTo(state(execution, answers));
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, NOT_STARTED, START, false",
            "CREATED, NOT_STARTED, RESPONSE_RECORDED, false",
            "CREATED, NOT_STARTED, SUBMIT, false",
            "IN_PROGRESS, NOT_STARTED, START, true",
            "IN_PROGRESS, NOT_STARTED, RESPONSE_RECORDED, false",
            "IN_PROGRESS, NOT_STARTED, SUBMIT, false",
            "IN_PROGRESS, IN_PROGRESS, START, true",
            "IN_PROGRESS, IN_PROGRESS, RESPONSE_RECORDED, true",
            "IN_PROGRESS, IN_PROGRESS, SUBMIT, false",
            "SUBMITTED, COMPLETE, START, true",
            "SUBMITTED, COMPLETE, RESPONSE_RECORDED, true",
            "SUBMITTED, COMPLETE, SUBMIT, true"
    })
    void verifiedIdenticalReplayRequiresConsistentCommittedState(Execution execution, AnswerCollection answers,
                                                               Event event, boolean replayAllowed) {
        var input = state(execution, answers);
        var expected = replayAllowed ? new Replay(input) : new Denied(REPLAY_STATE_MISMATCH);
        assertThat(evaluate(input, event, Context.identicalReplay())).isEqualTo(expected);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> allValidPairs() {
        return Stream.of(state(Execution.CREATED, AnswerCollection.NOT_STARTED),
                        state(Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED),
                        state(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS),
                        state(Execution.SUBMITTED, AnswerCollection.COMPLETE))
                .flatMap(state -> Arrays.stream(Event.values())
                        .map(event -> org.junit.jupiter.params.provider.Arguments.of(state, event)));
    }

    @ParameterizedTest
    @MethodSource("allValidPairs")
    void conflictingCommandIdentityNeverBecomesReplayOrNewEffect(State state, Event event) {
        assertThat(evaluate(state, event, new Context(CommandIdentity.CONFLICTING, facts(ResponseState.EMPTY))))
                .isEqualTo(new Denied(CONFLICTING_REPLAY));
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, IN_PROGRESS", "CREATED, COMPLETE", "IN_PROGRESS, COMPLETE",
            "SUBMITTED, NOT_STARTED", "SUBMITTED, IN_PROGRESS"
    })
    void contradictoryExecutionAndCollectionCannotBeRepresented(Execution execution, AnswerCollection answers) {
        rejected(() -> state(execution, answers), CONTRADICTORY_STATE);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownVersionsNeverSelectTheCurrentContract(int version) {
        rejected(() -> new State(version, Execution.CREATED, AnswerCollection.NOT_STARTED), UNSUPPORTED_VERSION);
        rejected(() -> new CompletionFacts(version, FinalizationMode.LOCK_ON_ACCEPTANCE, false, true,
                List.of(new Item(ITEM, ResponseState.EMPTY, Scope.APPLICABLE))), UNSUPPORTED_VERSION);
        rejected(() -> AttemptLifecycle.parseValue(version, Event.class, "START"), UNSUPPORTED_VERSION);
    }

    @Test
    void missingStateEventAndGuardsDenyWithoutDefaulting() {
        var created = state(Execution.CREATED, AnswerCollection.NOT_STARTED);
        var active = state(Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED);
        assertThat(evaluate(null, Event.START, Context.fresh())).isEqualTo(new Denied(INVALID_INPUT));
        assertThat(evaluate(created, null, Context.fresh())).isEqualTo(new Denied(INVALID_INPUT));
        assertThat(evaluate(created, Event.START, null)).isEqualTo(new Denied(MISSING_CONTEXT));
        assertThat(evaluate(active, Event.SUBMIT, Context.fresh())).isEqualTo(new Denied(MISSING_CONTEXT));
        rejected(() -> state(null, AnswerCollection.NOT_STARTED), INVALID_INPUT);
        rejected(() -> state(Execution.CREATED, null), INVALID_INPUT);
        rejected(() -> new Context(null, null), INVALID_INPUT);
        rejected(() -> new Advance(null, Plan.none()), INVALID_INPUT);
        rejected(() -> new Advance(created, null), INVALID_INPUT);
        rejected(() -> new Replay(null), INVALID_INPUT);
        rejected(() -> new Denied(null), INVALID_INPUT);
    }

    @Test
    void startResponseAndTerminalSequenceNeverReopensOrRepeatsFinalization() {
        State created = state(Execution.CREATED, AnswerCollection.NOT_STARTED);
        State started = ((Advance) evaluate(created, Event.START, Context.fresh())).state();
        State collecting = ((Advance) evaluate(started, Event.RESPONSE_RECORDED, Context.fresh())).state();
        var completed = (Advance) evaluate(collecting, Event.SUBMIT, Context.submit(facts(ResponseState.ACCEPTED)));
        assertThat(completed.state()).isEqualTo(state(Execution.SUBMITTED, AnswerCollection.COMPLETE));
        assertThat(completed.state().terminal()).isTrue();
        assertThat(created.terminal()).isFalse();
        for (int retry = 0; retry < 10; retry++) {
            assertThat(evaluate(completed.state(), Event.SUBMIT, Context.identicalReplay()))
                    .isEqualTo(new Replay(completed.state()));
            for (Event event : Event.values()) {
                assertThat(evaluate(completed.state(), event, Context.fresh())).isEqualTo(new Denied(TERMINAL_STATE));
            }
        }
        assertThat(collecting).isEqualTo(state(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS));
    }

    @Test
    void collectionFactsCannotClaimAcceptedOrProvisionalWorkBeforeCollectionStarted() {
        for (var response : List.of(ResponseState.ACCEPTED, ResponseState.DRAFT, ResponseState.PENDING_CONFIRMATION)) {
            assertThat(evaluate(state(Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED), Event.SUBMIT,
                    Context.submit(facts(response)))).isEqualTo(new Denied(CONTRADICTORY_FACTS));
        }
        // An ordinary editable response may have been cleared before completion.
        assertThat(evaluate(state(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS), Event.SUBMIT,
                Context.submit(facts(ResponseState.EMPTY)))).isInstanceOf(Advance.class);
    }

    static State state(Execution execution, AnswerCollection answers) { return new State(1, execution, answers); }

    static CompletionFacts facts(ResponseState response) {
        return new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, false, true,
                List.of(new Item(ITEM, response, Scope.APPLICABLE)));
    }

    static void rejected(Runnable action, LifecycleException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LifecycleException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(reason);
            assertThat(ex).hasMessage("Attempt lifecycle rejected: " + reason).hasNoCause();
        });
    }
}
