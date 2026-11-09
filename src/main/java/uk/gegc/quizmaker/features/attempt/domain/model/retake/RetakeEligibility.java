package uk.gegc.quizmaker.features.attempt.domain.model.retake;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

/**
 * Pure D21/D22 allowance decision over complete, current server facts. This is neither an
 * authorization check nor a reservation. #549 must read/revalidate and consume atomically;
 * #836 supplies examination closure. No cached decision permits an online or offline start.
 */
public final class RetakeEligibility {
    public static final int VERSION = 1;

    private RetakeEligibility() { }

    public enum ContextKind { PUBLICATION, ASSIGNMENT }
    public enum Access { VERIFIED, DENIED, MISSING }
    public enum Entry { OPEN, CLOSED, UNKNOWN }
    public enum Knowledge { COMPLETE, UNAVAILABLE, LEGACY_UNCLASSIFIED }
    public enum Commit { COMMITTED, TECHNICAL_FAILURE_BEFORE_COMMIT, UNKNOWN }
    /** Fact vocabulary, not new runtime lifecycle transitions or mappings from legacy status. */
    public enum Execution { NOT_STARTED, ACTIVE, COMPLETED, EXPIRED, ABANDONED, CANCELLED }
    public enum ResultState { NONE, PENDING, AVAILABLE, INVALIDATED }
    public enum Outcome { ALLOW, DENY }
    public enum Reason {
        ALLOWED, INVALID_INPUT, ACCESS_DENIED, UNSUPPORTED_VERSION, INVALID_POLICY,
        FACTS_UNAVAILABLE, LEGACY_CLASSIFICATION_REQUIRED, INCONSISTENT_FACTS,
        UNKNOWN_OUTCOME, ENTRY_CLOSED, ACTIVE_ATTEMPT, LIMIT_EXHAUSTED, COOLDOWN, CLOCK_UNAVAILABLE
    }

    /** Subject and immutable entry identity are resolved by the server, never a request DTO. */
    public record Scope(UUID subjectId, ContextKind kind, UUID contextId, long contextVersion) {
        private boolean valid() {
            return subjectId != null && kind != null && contextId != null && contextVersion > 0;
        }
        @Override public String toString() { return "RetakeScope[redacted]"; }
    }

    /** null maximum means unlimited; zero means no allowance. No finite count/default is invented. */
    public record Policy(int version, Long maximumAttempts, Duration cooldown) {
        @Override public String toString() { return "RetakePolicy[version=" + version + "]"; }
    }

    /** No score, pass/fail, certificate or selected-result authority is needed to count a start. */
    public record ResultFact(int version, UUID attemptId, ResultState state) {
        /** Projects an actual #603 fact without copying scores or interpreting result selection. */
        public static ResultFact of(AutomaticResult result) {
            Objects.requireNonNull(result, "Missing result fact");
            return new ResultFact(result.version(), result.attemptId(), ResultState.AVAILABLE);
        }
        @Override public String toString() { return "RetakeResultFact[version=" + version + "]"; }
    }

    /**
     * id is the committed attempt ID, or the operation ID of a classified precommit failure.
     * A technical failure has NOT_STARTED, no timestamps and NONE result; an unknown commit
     * must be reconciled, never counted as zero. terminalAt is the actual lifecycle terminal
     * instant, not grading/release time. All committed starts count even without a valid result.
     */
    public record StartFact(int version, UUID id, Scope scope, Commit commit, Execution execution,
                            Instant startedAt, Instant terminalAt, ResultFact result) {
        /**
         * Projects the delivered #527 vocabulary. SUBMITTED is terminal even before grading;
         * CREATED is not proof of a committed start and is rejected by this decision boundary.
         * Future expiry/cancellation readers supply their explicitly verified terminal facts.
         */
        public static StartFact of(UUID id, Scope scope, Instant startedAt, Instant terminalAt,
                                   AttemptLifecycle.State state, ResultFact result) {
            Objects.requireNonNull(state, "Missing lifecycle fact");
            Execution execution = switch (state.execution()) {
                case CREATED -> Execution.NOT_STARTED;
                case IN_PROGRESS -> Execution.ACTIVE;
                case SUBMITTED -> Execution.COMPLETED;
            };
            return new StartFact(state.version(), id, scope, Commit.COMMITTED, execution, startedAt, terminalAt, result);
        }
        @Override public String toString() { return "RetakeStartFact[version=" + version + "]"; }
    }

    /**
     * An already authorized, audited immutable restoration after documented platform fault.
     * This value does not authorize/reset anything; the reader verifies the audit/actor/scope.
     * Duplicate IDs/audit receipts and restoration exceeding consumed starts are inconsistent.
     */
    public record Adjustment(int version, UUID id, Scope scope, UUID actorId, UUID auditId,
                              long restoredAttempts, Instant recordedAt) {
        @Override public String toString() { return "RetakeAdjustment[version=" + version + "]"; }
    }

    /** Complete scoped history, including explicit empty history. Missing/unavailable is not empty. */
    public record Facts(int version, Scope scope, Knowledge knowledge,
                        List<StartFact> starts, List<Adjustment> adjustments) {
        public Facts {
            starts = immutable(starts);
            adjustments = immutable(adjustments);
        }
        @Override public String toString() { return "RetakeFacts[version=" + version + "]"; }
    }

    /** VERIFIED covers current permission, ownership, organization and visibility at the caller. */
    public record Context(int version, Scope scope, Access access, Entry entry, Policy policy, Facts facts) {
        @Override public String toString() { return "RetakeContext[version=" + version + "]"; }
    }

    /** No counts, identities or private assignment references escape through the result. */
    public record Decision(int version, Outcome outcome, Reason reason, Instant nextEligibleAt) {
        public Decision {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(reason);
            if (version != VERSION || (outcome == Outcome.ALLOW) != (reason == Reason.ALLOWED)
                    || (nextEligibleAt != null) != (reason == Reason.COOLDOWN)) {
                throw new IllegalArgumentException("Invalid eligibility decision");
            }
        }
    }

    /** now comes from the application service's Clock; it is not accepted from HTTP/client data. */
    public static Decision evaluate(Context context, Instant now) {
        if (context == null) return denied(Reason.INVALID_INPUT);
        if (context.access() != Access.VERIFIED) return denied(Reason.ACCESS_DENIED);
        if (context.version() != VERSION) return denied(Reason.UNSUPPORTED_VERSION);
        if (context.scope() == null || !context.scope().valid() || context.entry() == null
                || context.entry() == Entry.UNKNOWN) return denied(Reason.INVALID_INPUT);
        if (context.entry() == Entry.CLOSED) return denied(Reason.ENTRY_CLOSED);
        var policy = context.policy();
        if (policy == null) return denied(Reason.INVALID_POLICY);
        if (policy.version() != VERSION) return denied(Reason.UNSUPPORTED_VERSION);
        if (policy.maximumAttempts() != null && policy.maximumAttempts() < 0
                || policy.cooldown() == null || policy.cooldown().isNegative()) return denied(Reason.INVALID_POLICY);
        var facts = context.facts();
        if (facts == null || facts.knowledge() == null || facts.knowledge() == Knowledge.UNAVAILABLE) {
            return denied(Reason.FACTS_UNAVAILABLE);
        }
        if (facts.version() != VERSION) return denied(Reason.UNSUPPORTED_VERSION);
        if (!context.scope().equals(facts.scope())) return denied(Reason.INCONSISTENT_FACTS);
        if (facts.knowledge() == Knowledge.LEGACY_UNCLASSIFIED) return denied(Reason.LEGACY_CLASSIFICATION_REQUIRED);
        if (facts.starts() == null || facts.adjustments() == null) return denied(Reason.FACTS_UNAVAILABLE);
        if (now == null) return denied(Reason.CLOCK_UNAVAILABLE);

        long consumed = 0;
        int active = 0;
        Instant latestTerminal = null;
        var ids = new HashSet<UUID>();
        for (StartFact start : facts.starts()) {
            if (start == null) return denied(Reason.INCONSISTENT_FACTS);
            if (start.version() != VERSION || start.result() != null && start.result().version() != VERSION) {
                return denied(Reason.UNSUPPORTED_VERSION);
            }
            if (start.id() == null || !ids.add(start.id()) || !context.scope().equals(start.scope())
                    || start.commit() == null || start.execution() == null || start.result() == null
                    || start.result().state() == null) return denied(Reason.INCONSISTENT_FACTS);
            if (start.commit() == Commit.UNKNOWN) return denied(Reason.UNKNOWN_OUTCOME);
            if (start.commit() == Commit.TECHNICAL_FAILURE_BEFORE_COMMIT) {
                if (start.execution() != Execution.NOT_STARTED || start.startedAt() != null || start.terminalAt() != null
                        || start.result().state() != ResultState.NONE || start.result().attemptId() != null) {
                    return denied(Reason.INCONSISTENT_FACTS);
                }
                continue;
            }
            if (start.execution() == Execution.NOT_STARTED || start.startedAt() == null || start.startedAt().isAfter(now)
                    || (start.result().state() == ResultState.NONE ? start.result().attemptId() != null
                    : !start.id().equals(start.result().attemptId()))) return denied(Reason.INCONSISTENT_FACTS);
            consumed++;
            if (start.execution() == Execution.ACTIVE) {
                if (start.terminalAt() != null || start.result().state() != ResultState.NONE) {
                    return denied(Reason.INCONSISTENT_FACTS);
                }
                active++;
            } else {
                if (start.terminalAt() == null || start.terminalAt().isBefore(start.startedAt())
                        || start.terminalAt().isAfter(now)) return denied(Reason.INCONSISTENT_FACTS);
                if (latestTerminal == null || start.terminalAt().isAfter(latestTerminal)) latestTerminal = start.terminalAt();
            }
        }
        long restored = 0;
        var audits = new HashSet<UUID>();
        var adjustmentIds = new HashSet<UUID>();
        for (Adjustment adjustment : facts.adjustments()) {
            if (adjustment == null) return denied(Reason.INCONSISTENT_FACTS);
            if (adjustment.version() != VERSION) return denied(Reason.UNSUPPORTED_VERSION);
            if (adjustment.id() == null || !adjustmentIds.add(adjustment.id()) || !context.scope().equals(adjustment.scope())
                    || adjustment.actorId() == null || adjustment.auditId() == null || !audits.add(adjustment.auditId())
                    || adjustment.restoredAttempts() <= 0 || adjustment.recordedAt() == null
                    || adjustment.recordedAt().isAfter(now)) return denied(Reason.INCONSISTENT_FACTS);
            try { restored = Math.addExact(restored, adjustment.restoredAttempts()); }
            catch (ArithmeticException overflow) { return denied(Reason.INCONSISTENT_FACTS); }
        }
        if (restored > consumed || active > 1) return denied(Reason.INCONSISTENT_FACTS);
        if (active == 1) return denied(Reason.ACTIVE_ATTEMPT);
        if (policy.maximumAttempts() != null && consumed - restored >= policy.maximumAttempts()) {
            return denied(Reason.LIMIT_EXHAUSTED);
        }
        if (latestTerminal != null) {
            Instant eligibleAt;
            try { eligibleAt = latestTerminal.plus(policy.cooldown()); }
            catch (DateTimeException | ArithmeticException overflow) { return denied(Reason.INVALID_POLICY); }
            if (now.isBefore(eligibleAt)) return new Decision(VERSION, Outcome.DENY, Reason.COOLDOWN, eligibleAt);
        }
        return new Decision(VERSION, Outcome.ALLOW, Reason.ALLOWED, null);
    }

    public static Decision denied(Reason reason) { return new Decision(VERSION, Outcome.DENY, reason, null); }

    // Preserve malformed null entries for a typed denial; never silently filter incomplete evidence.
    private static <T> List<T> immutable(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
