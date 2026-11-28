package uk.gegc.quizmaker.features.attempt.domain.model.activity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

import static uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException.require;

/**
 * Dark D23/D24/R14 contract, not an attempt engine, HTTP input or persisted completion receipt.
 * Callers resolve current authority and bind the published policy/requirements to the exact form
 * before start. Facts come from authoritative participation, never client claims or draft presence.
 * #567/#642 own real response/submit transactions; #695 owns activation. No kind grants disclosure.
 */
public final class ActivityContract {
    public static final int VERSION = 1;

    private ActivityContract() { }

    public enum Kind { GRADED, UNGRADED }
    public enum Classification { GRADED, UNGRADED, LEGACY }
    public enum ResponseRetention { RETAIN_IMMUTABLE_RESPONSES, COMPLETION_ONLY_NO_RESPONSE_RETENTION }
    public enum ResponseDisposition { RETAIN_IMMUTABLY, DO_NOT_RETAIN }
    public enum IncompleteState { NOT_STARTED, IN_PROGRESS, ABANDONED, EXPIRED }
    /** Candidate means comparable kind only, never current validity, release or certificate authority. */
    public enum AssessmentDisposition { GRADED_CANDIDATE, EXCLUDED_UNGRADED, LEGACY_NOT_COMPARABLE }

    public sealed interface Policy permits GradedPolicy, UngradedPolicy {
        int version();
        Kind kind();
    }

    /** Existing scored policy remains intact, including its units, thresholds and disclosure. */
    public record GradedPolicy(int version, ResolvedAttemptPolicy policy) implements Policy {
        public GradedPolicy { versioned(version); require(policy != null, INVALID_INPUT); }
        @Override public Kind kind() { return Kind.GRADED; }
        @Override public String toString() { return "GradedActivityPolicy[version=" + version + "]"; }
    }

    /** No scoring mode, threshold, score, correctness, self-rating or release grant is representable. */
    public record UngradedPolicy(int version, ResponseRetention responseRetention,
                                 ParticipationRequirements participation) implements Policy {
        public UngradedPolicy {
            versioned(version);
            require(responseRetention != null && participation != null, INVALID_INPUT);
        }
        @Override public Kind kind() { return Kind.UNGRADED; }
        @Override public String toString() { return "UngradedActivityPolicy[version=" + version + "]"; }
    }

    /** Form-owned membership and required/optional participation, captured before the activity starts. */
    public record ParticipationRequirements(List<ItemRequirement> items) {
        public ParticipationRequirements {
            require(items != null, INVALID_INPUT);
            var identities = new HashSet<UUID>();
            for (var item : items) require(item != null && identities.add(item.formItemId()), INVALID_INPUT);
            items = List.copyOf(items);
        }
        @Override public String toString() { return "ParticipationRequirements[itemCount=" + items.size() + "]"; }
    }

    public record ItemRequirement(UUID formItemId, boolean required) {
        public ItemRequirement { require(formItemId != null, INVALID_INPUT); }
        @Override public String toString() { return "ActivityItemRequirement[redacted]"; }
    }

    /** The start owner proves presentation before starting; this boolean is not a consent credential. */
    public record StartFacts(boolean privacyNoticePresented) { }

    public sealed interface Outcome permits GradedOutcome, UngradedOutcome {
        int version();
        Kind kind();
    }

    public record GradedOutcome(int version, AutomaticResult result) implements Outcome {
        public GradedOutcome { versioned(version); require(result != null, INVALID_INPUT); }
        @Override public Kind kind() { return Kind.GRADED; }
        @Override public String toString() { return "GradedActivityOutcome[version=" + version + "]"; }
    }

    public sealed interface UngradedOutcome extends Outcome permits Incomplete, Completed {
        @Override default Kind kind() { return Kind.UNGRADED; }
    }

    /** Abandonment and expiry are explicitly incomplete, without a completion timestamp or grade. */
    public record Incomplete(int version, IncompleteState state) implements UngradedOutcome {
        public Incomplete { versioned(version); require(state != null, INVALID_INPUT); }
    }

    public record Completed(int version, Instant completedAt) implements UngradedOutcome {
        public Completed {
            versioned(version);
            require(completedAt != null, INVALID_INPUT);
            completedAt = completedAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    /**
     * Payload-free server facts. A participated item need not have a retained response in no-retention
     * mode. The writer must establish actual policy-required participation before supplying these IDs.
     * This value is not a public request, stored response, idempotency key or authorization decision.
     */
    public record CompletionEvidence(boolean explicitFinalSubmit, List<UUID> participatedItems, Instant occurredAt) {
        public CompletionEvidence {
            require(participatedItems != null && occurredAt != null, INVALID_INPUT);
            var identities = new HashSet<UUID>();
            for (var id : participatedItems) require(id != null && identities.add(id), INVALID_INPUT);
            participatedItems = List.copyOf(participatedItems);
        }
        @Override public String toString() { return "ActivityCompletionEvidence[redacted]"; }
    }

    public static Incomplete start(UngradedPolicy policy, StartFacts facts) {
        require(policy != null && facts != null, INVALID_INPUT);
        require(policy.responseRetention() != ResponseRetention.COMPLETION_ONLY_NO_RESPONSE_RETENTION
                || facts.privacyNoticePresented(), PRIVACY_NOTICE_REQUIRED);
        return new Incomplete(VERSION, IncompleteState.IN_PROGRESS);
    }

    /** A storage instruction, never an instruction to invoke a question scorer or expose feedback. */
    public static ResponseDisposition responseDisposition(UngradedPolicy policy, UngradedOutcome current) {
        require(policy != null && current != null, INVALID_INPUT);
        require(inProgress(current), NOT_IN_PROGRESS);
        return switch (policy.responseRetention()) {
            case RETAIN_IMMUTABLE_RESPONSES -> ResponseDisposition.RETAIN_IMMUTABLY;
            case COMPLETION_ONLY_NO_RESPONSE_RETENTION -> ResponseDisposition.DO_NOT_RETAIN;
        };
    }

    /** Pure plan. Durable identity/replay, form binding and atomic application belong to the writer. */
    public static Completed complete(UngradedPolicy policy, UngradedOutcome current, CompletionEvidence facts) {
        require(policy != null && current != null && facts != null, INVALID_INPUT);
        require(inProgress(current), NOT_IN_PROGRESS);
        require(facts.explicitFinalSubmit(), FINAL_SUBMIT_REQUIRED);
        var unmatchedParticipation = new HashSet<>(facts.participatedItems());
        var missing = new ArrayList<UUID>();
        for (var item : policy.participation().items()) {
            boolean participated = unmatchedParticipation.remove(item.formItemId());
            if (item.required() && !participated) missing.add(item.formItemId());
        }
        require(unmatchedParticipation.isEmpty(), CONTRADICTORY_STATE);
        if (!missing.isEmpty()) throw new ActivityContractException(PARTICIPATION_REQUIRED, missing);
        return new Completed(VERSION, facts.occurredAt());
    }

    /** Missing historical metadata stays legacy; one missing field is corruption, not a default. */
    public static Classification classify(Integer version, String kind) {
        if (version == null && kind == null) return Classification.LEGACY;
        require(version != null && kind != null, INVALID_INPUT);
        versioned(version);
        return switch (parseKind(kind)) {
            case GRADED -> Classification.GRADED;
            case UNGRADED -> Classification.UNGRADED;
        };
    }

    /** Shared exclusion input for later score/pass/best/latest/ranking/certificate consumers. */
    public static AssessmentDisposition assessmentDisposition(Classification classification) {
        require(classification != null, INVALID_INPUT);
        return switch (classification) {
            case GRADED -> AssessmentDisposition.GRADED_CANDIDATE;
            case UNGRADED -> AssessmentDisposition.EXCLUDED_UNGRADED;
            case LEGACY -> AssessmentDisposition.LEGACY_NOT_COMPARABLE;
        };
    }

    /** Graded-only consumer guard; this contract never calls an application scorer. */
    public static ResolvedAttemptPolicy requireGrading(Policy policy) {
        require(policy != null, INVALID_INPUT);
        require(policy instanceof GradedPolicy, NOT_GRADED);
        return ((GradedPolicy) policy).policy();
    }

    public static AutomaticResult assessedResult(Outcome outcome) {
        require(outcome != null, INVALID_INPUT);
        require(outcome instanceof GradedOutcome, NOT_GRADED);
        return ((GradedOutcome) outcome).result();
    }

    public static Kind parseKind(String value) {
        require(value != null, INVALID_INPUT);
        for (var kind : Kind.values()) if (kind.name().equals(value)) return kind;
        throw new ActivityContractException(UNKNOWN_KIND);
    }

    /** Closed contract vocabulary only, without aliases, ordinal coercion or echoing rejected text. */
    public static <E extends Enum<E>> E parseValue(Class<E> type, String value) {
        require(type != null && value != null && type.isEnum()
                && type.getDeclaringClass() == ActivityContract.class, INVALID_INPUT);
        for (var candidate : type.getEnumConstants()) if (candidate.name().equals(value)) return candidate;
        throw new ActivityContractException(UNKNOWN_VALUE);
    }

    private static boolean inProgress(UngradedOutcome value) {
        return value instanceof Incomplete state && state.state() == IncompleteState.IN_PROGRESS;
    }

    private static void versioned(int version) { require(version == VERSION, UNSUPPORTED_VERSION); }
}
