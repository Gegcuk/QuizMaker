package uk.gegc.quizmaker.features.attempt.domain.model.lifecycle;

import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException.require;

/**
 * Pure, dark #527 v1 execution/answer-collection contract for the authenticated flat ALL slice.
 * Results describe lifecycle eligibility only: no authentication, ownership, response validation,
 * command identity, persistence, grading or release authority is granted here.
 */
public final class AttemptLifecycle {
    public static final int VERSION = 1;

    private AttemptLifecycle() {}

    public enum Execution { CREATED, IN_PROGRESS, SUBMITTED }
    /** COMPLETE means collection closed, even when the form contains unanswered items. */
    public enum AnswerCollection { NOT_STARTED, IN_PROGRESS, COMPLETE }
    public enum Event { START, RESPONSE_RECORDED, SUBMIT }
    /** The caller verifies operation/scope/key/input identity through #599, never from status alone. */
    public enum CommandIdentity { FRESH, VERIFIED_IDENTICAL, CONFLICTING }

    public record State(int version, Execution execution, AnswerCollection answers) {
        public State {
            require(version == VERSION, UNSUPPORTED_VERSION);
            require(execution != null && answers != null, INVALID_INPUT);
            boolean consistent = switch (execution) {
                case CREATED -> answers == AnswerCollection.NOT_STARTED;
                case IN_PROGRESS -> answers != AnswerCollection.COMPLETE;
                case SUBMITTED -> answers == AnswerCollection.COMPLETE;
            };
            require(consistent, CONTRADICTORY_STATE);
        }

        public boolean terminal() { return execution == Execution.SUBMITTED; }
    }

    public record Context(CommandIdentity identity, CompletionFacts completion) {
        public Context { require(identity != null, INVALID_INPUT); }

        public static Context fresh() { return new Context(CommandIdentity.FRESH, null); }
        public static Context submit(CompletionFacts facts) { return new Context(CommandIdentity.FRESH, facts); }
        public static Context identicalReplay() { return new Context(CommandIdentity.VERIFIED_IDENTICAL, null); }
    }

    public sealed interface Decision permits Advance, Replay, Denied {}

    /** A plan must be applied atomically by #529/#834; returning this value performs no writes. */
    public record Advance(State state, CompletionFacts.Plan plan) implements Decision {
        public Advance { require(state != null && plan != null, INVALID_INPUT); }
    }

    /** No new effect. The original command acknowledgement is owned by its durable receipt. */
    public record Replay(State state) implements Decision {
        public Replay { require(state != null, INVALID_INPUT); }
    }

    public record Denied(LifecycleException.Reason reason) implements Decision {
        public Denied { require(reason != null, INVALID_INPUT); }
    }

    public static Decision evaluate(State state, Event event, Context context) {
        if (state == null || event == null) return new Denied(INVALID_INPUT);
        if (context == null) return new Denied(MISSING_CONTEXT);
        if (context.identity() == CommandIdentity.CONFLICTING) return new Denied(CONFLICTING_REPLAY);
        if (context.identity() == CommandIdentity.VERIFIED_IDENTICAL) return replay(state, event);
        // No fresh event may change or reopen a terminal tuple, including response activity.
        if (state.terminal()) return new Denied(TERMINAL_STATE);
        return switch (event) {
            case START -> state.execution() == Execution.CREATED
                    ? advance(Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED, CompletionFacts.Plan.none())
                    : new Denied(ALREADY_STARTED);
            case RESPONSE_RECORDED -> state.execution() == Execution.IN_PROGRESS
                    ? advance(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS, CompletionFacts.Plan.none())
                    : new Denied(NOT_STARTED);
            case SUBMIT -> submit(state, context.completion());
        };
    }

    private static Decision replay(State state, Event event) {
        boolean effectReached = switch (event) {
            case START -> state.execution() != Execution.CREATED;
            case RESPONSE_RECORDED -> state.answers() != AnswerCollection.NOT_STARTED;
            case SUBMIT -> state.terminal();
        };
        return effectReached ? new Replay(state) : new Denied(REPLAY_STATE_MISMATCH);
    }

    private static Decision submit(State state, CompletionFacts completion) {
        if (state.execution() != Execution.IN_PROGRESS) return new Denied(NOT_STARTED);
        if (completion == null) return new Denied(MISSING_CONTEXT);
        // Clearing provisional work does not erase the fact that collection already started.
        if (state.answers() == AnswerCollection.NOT_STARTED && completion.hasResponseActivity()) {
            return new Denied(CONTRADICTORY_FACTS);
        }
        try {
            return advance(Execution.SUBMITTED, AnswerCollection.COMPLETE, completion.plan());
        } catch (LifecycleException ex) {
            return new Denied(ex.reason());
        }
    }

    private static Advance advance(Execution execution, AnswerCollection answers, CompletionFacts.Plan plan) {
        return new Advance(new State(VERSION, execution, answers), plan);
    }

    /** Exact v1 symbolic vocabulary only; unknown versions/names never select current defaults. */
    public static <E extends Enum<E>> E parseValue(int version, Class<E> type, String value) {
        require(version == VERSION, UNSUPPORTED_VERSION);
        require(type != null && value != null, INVALID_INPUT);
        require(type.isEnum() && (type.getDeclaringClass() == AttemptLifecycle.class
                || type.getDeclaringClass() == CompletionFacts.class), INVALID_INPUT);
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(value)) return candidate;
        }
        throw new LifecycleException(UNKNOWN_VALUE);
    }
}
