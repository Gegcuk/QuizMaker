package uk.gegc.quizmaker.features.attempt.domain.model.command;

import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.ResponseState;

/**
 * Pure, dark #523 decisions for authenticated flat ALL practice. These are eligibility decisions,
 * not handlers, authorization proofs, state transitions, response validation or release grants.
 * Context/metadata are trusted server facts, never participant request DTOs.
 */
public final class AttemptCommands {
    public static final int VERSION = 1;

    private AttemptCommands() {}

    /** Only the initial participant command vocabulary; future capabilities fail closed. */
    public enum Type { START, VIEW_ATTEMPT, NAVIGATE_TO_ITEM, SUBMIT_ANSWER, SUBMIT_ATTEMPT }
    public enum Outcome { ALLOWED, REPLAY, DENIED, STALE, CONFLICT }
    /** VERIFIED means the consumer checked current identity, ownership and every applicable boundary. */
    public enum Access { VERIFIED, DENIED, MISSING }
    public enum Reason {
        ALLOWED, VERIFIED_REPLAY, INVALID_INPUT, UNSUPPORTED_VERSION, UNKNOWN_COMMAND,
        MISSING_CONTEXT, ACCESS_DENIED, MISSING_METADATA, INVALID_METADATA,
        STALE_VERSION, CONFLICTING_REPLAY, ITEM_UNAVAILABLE, ANSWER_LOCKED,
        UNSUPPORTED_RESPONSE_STATE, POLICY_FACTS_MISMATCH, LIFECYCLE_DENIED
    }

    /** Contains no client assertion about allowed actions, mode, status, subject or next state. */
    public record Command(int version, Type type) {}

    /**
     * Supplied by the command consumer after receipt resolution (#599). operationId is an internal
     * bounded receipt identity, not a restriction on the future transport idempotency-key format.
     * Identity must cover operation, subject/context, target and canonical input; a key alone proves
     * nothing. A verified replay retrieves its original acknowledgement from that receipt.
     */
    public record Metadata(long expectedVersion, UUID operationId, CommandIdentity identity) {}

    /** The consumer resolves exact immutable form membership, delivery and saved response state. */
    public record ItemFacts(boolean member, boolean delivered, ResponseState response) {}

    /**
     * stateVersion is the authoritative optimistic-concurrency counter, not lifecycle schemaVersion.
     * completion contains every form item in saved order; only SUBMIT_ATTEMPT requires it.
     * Missing/denied access may be represented without loading protected policy/state/item facts.
     */
    public record Context(Access access, ResolvedAttemptPolicy policy, State state, long stateVersion,
                          ItemFacts item, CompletionFacts completion) {}

    /** Safe acknowledgement classification only; carries no existence, reason, IDs, state or content. */
    public record Acknowledgement(Outcome outcome) {
        public Acknowledgement { Objects.requireNonNull(outcome); }
    }

    /** Internal only: HTTP/view consumers expose acknowledgement(), never serialize this diagnostic. */
    public record Decision(Reason reason, LifecycleException.Reason lifecycleReason) {
        public Decision {
            Objects.requireNonNull(reason);
            if ((reason == Reason.LIFECYCLE_DENIED) != (lifecycleReason != null)) {
                throw new IllegalArgumentException("Invalid command decision");
            }
        }

        public Outcome outcome() {
            return switch (reason) {
                case ALLOWED -> Outcome.ALLOWED;
                case VERIFIED_REPLAY -> Outcome.REPLAY;
                case STALE_VERSION -> Outcome.STALE;
                case CONFLICTING_REPLAY -> Outcome.CONFLICT;
                default -> Outcome.DENIED;
            };
        }

        public Acknowledgement acknowledgement() { return new Acknowledgement(outcome()); }
    }

    /** Deterministic enum order, immutable, and advisory: every actual command must be reevaluated. */
    public record AllowedActions(List<Type> actions) {
        public AllowedActions { actions = List.copyOf(actions); }
        public boolean contains(Type type) { return actions.contains(type); }
    }

    public static Decision evaluate(Command command, Context context, Metadata metadata) {
        Decision invalid = validateContext(context);
        if (invalid != null) return invalid;
        if (command == null || command.type() == null) return decision(Reason.INVALID_INPUT);
        if (command.version() != VERSION) return decision(Reason.UNSUPPORTED_VERSION);
        // Reading a safe participant view does not mutate state or require a command receipt.
        if (command.type() == Type.VIEW_ATTEMPT) return eligibility(command.type(), context);
        if (metadata == null) return decision(Reason.MISSING_METADATA);
        if (metadata.expectedVersion() < 0 || metadata.operationId() == null || metadata.identity() == null) {
            return decision(Reason.INVALID_METADATA);
        }
        if (metadata.identity() == CommandIdentity.CONFLICTING) return decision(Reason.CONFLICTING_REPLAY);
        // A previously committed, verified identical command need not match today's state version.
        // Current authorization and supported versions still precede this no-effect decision.
        if (metadata.identity() == CommandIdentity.VERIFIED_IDENTICAL) return replay(command.type(), context);
        if (metadata.expectedVersion() != context.stateVersion()) return decision(Reason.STALE_VERSION);
        return eligibility(command.type(), context);
    }

    public static AllowedActions allowedActions(Context context) {
        if (validateContext(context) != null) return new AllowedActions(List.of());
        return new AllowedActions(Arrays.stream(Type.values())
                .filter(type -> eligibility(type, context).outcome() == Outcome.ALLOWED).toList());
    }

    private static Decision validateContext(Context context) {
        if (context == null) return decision(Reason.MISSING_CONTEXT);
        // No protected existence, state version or item facts are inspected before this gate.
        if (context.access() != Access.VERIFIED) return decision(Reason.ACCESS_DENIED);
        if (context.policy() == null || context.state() == null) return decision(Reason.MISSING_CONTEXT);
        if (context.stateVersion() < 0) return decision(Reason.INVALID_INPUT);
        return null;
    }

    private static Decision eligibility(Type type, Context context) {
        return switch (type) {
            case START -> lifecycle(context, Event.START, AttemptLifecycle.Context.fresh());
            case VIEW_ATTEMPT -> context.state().execution() == Execution.CREATED
                    ? lifecycleDenied(LifecycleException.Reason.NOT_STARTED) : decision(Reason.ALLOWED);
            case NAVIGATE_TO_ITEM, SUBMIT_ANSWER -> itemEligibility(type, context);
            case SUBMIT_ATTEMPT -> completionEligibility(context);
        };
    }

    private static Decision itemEligibility(Type type, Context context) {
        if (context.state().execution() != Execution.IN_PROGRESS) {
            return lifecycleDenied(context.state().terminal()
                    ? LifecycleException.Reason.TERMINAL_STATE : LifecycleException.Reason.NOT_STARTED);
        }
        var item = context.item();
        if (item == null || !item.member() || !item.delivered() || item.response() == null) {
            return decision(Reason.ITEM_UNAVAILABLE);
        }
        if (item.response() != ResponseState.EMPTY && item.response() != ResponseState.ACCEPTED) {
            return decision(Reason.UNSUPPORTED_RESPONSE_STATE);
        }
        if (item.response() == ResponseState.ACCEPTED && context.state().answers() == AnswerCollection.NOT_STARTED) {
            return lifecycleDenied(LifecycleException.Reason.CONTRADICTORY_FACTS);
        }
        if (type == Type.NAVIGATE_TO_ITEM) return decision(Reason.ALLOWED);
        if (item.response() == ResponseState.ACCEPTED) return decision(Reason.ANSWER_LOCKED);
        return lifecycle(context, Event.RESPONSE_RECORDED, AttemptLifecycle.Context.fresh());
    }

    private static Decision completionEligibility(Context context) {
        // Let #527 determine NOT_STARTED/TERMINAL_STATE before asking for completion facts.
        if (context.state().execution() != Execution.IN_PROGRESS || context.completion() == null) {
            return lifecycle(context, Event.SUBMIT, AttemptLifecycle.Context.fresh());
        }
        var facts = context.completion();
        if (facts.finalization() != CompletionFacts.FinalizationMode.LOCK_ON_ACCEPTANCE
                || facts.requireAllAnswers() != context.policy().submission().requireAllAnswers()) {
            return decision(Reason.POLICY_FACTS_MISMATCH);
        }
        return lifecycle(context, Event.SUBMIT, AttemptLifecycle.Context.submit(facts));
    }

    private static Decision replay(Type type, Context context) {
        return switch (type) {
            case START -> lifecycle(context, Event.START, AttemptLifecycle.Context.identicalReplay());
            case SUBMIT_ANSWER -> lifecycle(context, Event.RESPONSE_RECORDED, AttemptLifecycle.Context.identicalReplay());
            case SUBMIT_ATTEMPT -> lifecycle(context, Event.SUBMIT, AttemptLifecycle.Context.identicalReplay());
            // Receipt identity proves the original navigation; current terminality cannot undo it.
            case NAVIGATE_TO_ITEM -> context.state().execution() == Execution.CREATED
                    ? lifecycleDenied(LifecycleException.Reason.REPLAY_STATE_MISMATCH)
                    : decision(Reason.VERIFIED_REPLAY);
            case VIEW_ATTEMPT -> eligibility(type, context);
        };
    }

    private static Decision lifecycle(Context context, Event event, AttemptLifecycle.Context guards) {
        var result = AttemptLifecycle.evaluate(context.state(), event, guards);
        if (result instanceof AttemptLifecycle.Denied denied) return lifecycleDenied(denied.reason());
        return decision(result instanceof AttemptLifecycle.Replay ? Reason.VERIFIED_REPLAY : Reason.ALLOWED);
    }

    private static Decision decision(Reason reason) { return new Decision(reason, null); }
    private static Decision lifecycleDenied(LifecycleException.Reason reason) {
        return new Decision(Reason.LIFECYCLE_DENIED, reason);
    }

    /** Exact v1 names only; no legacy mode/status, case folding or current-version fallback. */
    public static Type parseType(int version, String value) {
        if (version != VERSION) throw new AttemptCommandException(Reason.UNSUPPORTED_VERSION);
        if (value == null) throw new AttemptCommandException(Reason.INVALID_INPUT);
        for (Type type : Type.values()) if (type.name().equals(value)) return type;
        throw new AttemptCommandException(Reason.UNKNOWN_COMMAND);
    }
}
