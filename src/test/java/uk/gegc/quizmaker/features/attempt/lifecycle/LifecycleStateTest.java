package uk.gegc.quizmaker.features.attempt.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleStateException.Reason.*;

class LifecycleStateTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000528");
    static final Instant NOW = Instant.parse("2026-10-27T12:00:00.123456Z");
    static final State CREATED = new State(1, Execution.CREATED, AnswerCollection.NOT_STARTED);
    static final State STARTED = new State(1, Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED);
    static final State COLLECTING = new State(1, Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS);
    static final State SUBMITTED = new State(1, Execution.SUBMITTED, AnswerCollection.COMPLETE);

    @Test
    void storageVersionAndClockMetadataAreIndependentOfTheLifecycleContractVersion() {
        var created = LifecycleState.initial(ID, NOW.plusNanos(999));
        assertThat(created).isEqualTo(new LifecycleState(ID, 0, CREATED, NOW, NOW, null, null));
        var started = created.advance(STARTED, NOW.plusSeconds(1));
        var first = started.advance(COLLECTING, NOW.plusSeconds(2));
        var second = first.advance(COLLECTING, NOW.plusSeconds(3));
        var terminal = second.advance(SUBMITTED, NOW.plusSeconds(4));
        assertThat(terminal.rowVersion()).isEqualTo(4);
        assertThat(terminal.state().version()).isEqualTo(1);
        assertThat(terminal.createdAt()).isEqualTo(NOW);
        assertThat(terminal.startedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(terminal.submittedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(terminal.updatedAt()).isEqualTo(terminal.submittedAt());
        assertThat(created.rowVersion()).isZero();
        assertThat(started.submittedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "0,0,false", "0,1,true", "0,2,false", "0,3,false",
            "1,0,false", "1,1,false", "1,2,true", "1,3,true",
            "2,0,false", "2,1,false", "2,2,true", "2,3,true",
            "3,0,false", "3,1,false", "3,2,false", "3,3,false"
    })
    void storageCannotSkipStartRegressOrRewriteTerminalEvidence(int before, int after, boolean allowed) {
        var states = List.of(CREATED, STARTED, COLLECTING, SUBMITTED);
        var state = new LifecycleState(ID, before, states.get(before), NOW, NOW,
                before == 0 ? null : NOW, before == 3 ? NOW : null);
        if (allowed) assertThat(state.advance(states.get(after), NOW).state()).isEqualTo(states.get(after));
        else rejected(() -> state.advance(states.get(after), NOW), INVALID_TRANSITION);
    }

    @Test
    void everyFreshPureContractAdvanceCanBeStoredWithoutExecutingTheCommand() {
        var item = new CompletionFacts.Item(ID, CompletionFacts.ResponseState.EMPTY, CompletionFacts.Scope.APPLICABLE);
        var facts = new CompletionFacts(1, CompletionFacts.FinalizationMode.LOCK_ON_ACCEPTANCE, false, true, List.of(item));
        for (State state : List.of(CREATED, STARTED, COLLECTING, SUBMITTED)) {
            for (Event event : Event.values()) {
                var decision = AttemptLifecycle.evaluate(state, event, Context.submit(facts));
                if (decision instanceof Advance advance) {
                    var stored = new LifecycleState(ID, 5, state, NOW, NOW,
                            state.execution() == Execution.CREATED ? null : NOW, state.terminal() ? NOW : null);
                    assertThat(stored.advance(advance.state(), NOW).state()).isEqualTo(advance.state());
                }
            }
        }
    }

    @Test
    void malformedMetadataAndEngineReferencesFailWithBoundedErrors() {
        rejected(() -> LifecycleState.initial(null, NOW), INVALID_INPUT);
        rejected(() -> LifecycleState.initial(ID, null), INVALID_INPUT);
        rejected(() -> new LifecycleReference.Target(null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, -1, CREATED, NOW, NOW, null, null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 0, null, NOW, NOW, null, null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 0, CREATED, NOW, NOW.minusSeconds(1), null, null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 0, CREATED, NOW, NOW, NOW, null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 1, STARTED, NOW, NOW, null, null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 1, STARTED, NOW, NOW, NOW, NOW), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 2, SUBMITTED, NOW, NOW, NOW, null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 2, SUBMITTED, NOW, NOW, NOW, NOW.minusSeconds(1)), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 1, STARTED, NOW, NOW, NOW.minusSeconds(1), null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 1, STARTED, NOW, NOW, NOW.plusSeconds(1), null), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, 2, SUBMITTED, NOW, NOW, NOW, NOW.plusSeconds(1)), INVALID_INPUT);
        rejected(() -> LifecycleState.initial(ID, NOW).advance(null, NOW), INVALID_INPUT);
        rejected(() -> LifecycleState.initial(ID, NOW).advance(STARTED, NOW.minusSeconds(1)), INVALID_INPUT);
        rejected(() -> new LifecycleState(ID, Long.MAX_VALUE, STARTED, NOW, NOW, NOW, null)
                .advance(COLLECTING, NOW), STATE_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0999-12-31T23:59:59Z", "10000-01-01T00:00:00Z"})
    void datesOutsideTheExactMysqlRepresentationAreRejected(String value) {
        rejected(() -> LifecycleState.initial(ID, Instant.parse(value.startsWith("10000") ? "+" + value : value)), INVALID_INPUT);
    }

    static void rejected(Runnable action, LifecycleStateException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LifecycleStateException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessage("Lifecycle state rejected: " + reason).hasNoCause();
    }
}
