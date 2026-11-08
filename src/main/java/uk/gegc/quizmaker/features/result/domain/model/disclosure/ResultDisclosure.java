package uk.gegc.quizmaker.features.result.domain.model.disclosure;

import java.util.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResult;

import static uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;

/**
 * Pure #555 terminal result/review contract. Inputs are current, verified server facts, never
 * request DTOs or reusable capabilities. This contract neither authenticates nor reads/writes
 * release state. #556 supplies committed release evidence and participant projection.
 * Active per-item feedback after acceptance (D03) has a separate boundary (#605).
 */
public final class ResultDisclosure {
    public static final int VERSION = 1;

    private ResultDisclosure() { }

    public enum Category {
        TOTAL_SCORE, PASS_FAIL, QUESTION_SCORE, CORRECTNESS, CORRECT_ANSWER, EXPLANATION,
        FEEDBACK, SUBMITTED_ANSWERS, REVIEW
    }
    public enum Outcome { ALLOW, DENY, PENDING }
    public enum Reason {
        ALLOWED, INVALID_INPUT, ACCESS_DENIED, UNSUPPORTED_VERSION, INCONSISTENT_FACTS,
        ATTEMPT_NOT_SUBMITTED, POLICY_DENIED, RESULT_PENDING, RELEASE_PENDING,
        RELEASE_REVOKED, REVIEW_UNAVAILABLE, CONTENT_UNAVAILABLE
    }
    public enum Access { VERIFIED, DENIED, MISSING }
    public enum Family { PERSONAL_PRACTICE, FORMAL_ASSESSMENT }
    public enum ResultState { PENDING, FINAL }
    public enum ReleaseState { PENDING, RELEASED, REVOKED }
    public enum ReviewAccess { AVAILABLE, WITHHELD, REVOKED }

    /** Bounded immutable metadata only; policy hashes are already verified by the snapshot reader. */
    public record Binding(UUID attemptId, UUID formId, UUID policySnapshotId, UUID quizRevisionId,
                          String policyHash) {
        public static Binding of(AutomaticResult result) {
            Objects.requireNonNull(result, "Missing result");
            return new Binding(result.attemptId(), result.formId(), result.policySnapshotId(),
                    result.quizRevisionId(), result.policyHash());
        }
        private boolean valid() {
            return attemptId != null && formId != null && policySnapshotId != null && quizRevisionId != null
                    && policyHash != null && policyHash.length() == 64 && policyHash.matches("[0-9a-f]{64}");
        }
        @Override public String toString() { return "DisclosureBinding"; }
    }

    /** VERIFIED includes the current RESULT permission, resource, organization and visibility checks. */
    public record Audience(Access access, UUID attemptId, UUID authenticatedUserId, UUID boundUserId) {
        @Override public String toString() { return "DisclosureAudience"; }
    }

    /** Exact captured allow-list, not an author/client override or a release trigger. */
    public record Policy(int version, Binding binding, Family family, Set<Category> permitted) {
        public Policy { permitted = immutableCategories(permitted); }

        /** Adapt the existing saved practice vocabulary without changing its schema/hash. */
        public static Policy practice(Binding binding, ResolvedAttemptPolicy saved) {
            if (binding == null || !binding.valid() || saved == null
                    || !binding.quizRevisionId().equals(saved.content().quizRevisionId())) {
                throw new IllegalArgumentException("Invalid practice disclosure policy");
            }
            var categories = EnumSet.noneOf(Category.class);
            if (saved.disclosure().releaseOf(DisclosureCategory.TOTAL_SCORE) == ReleaseMoment.AFTER_COMPLETION)
                categories.add(Category.TOTAL_SCORE);
            if (saved.disclosure().releaseOf(DisclosureCategory.QUESTION_SCORE) == ReleaseMoment.AFTER_ACCEPTANCE)
                categories.add(Category.QUESTION_SCORE);
            if (saved.review().content().contains(ReviewCategory.CORRECTNESS)
                    && saved.disclosure().releaseOf(DisclosureCategory.CORRECTNESS) != ReleaseMoment.NEVER)
                categories.add(Category.CORRECTNESS);
            if (saved.review().content().contains(ReviewCategory.CORRECT_ANSWERS)
                    && saved.disclosure().releaseOf(DisclosureCategory.CORRECT_ANSWER) != ReleaseMoment.NEVER)
                categories.add(Category.CORRECT_ANSWER);
            if (saved.review().content().contains(ReviewCategory.EXPLANATIONS)
                    && saved.disclosure().releaseOf(DisclosureCategory.EXPLANATION) != ReleaseMoment.NEVER)
                categories.add(Category.EXPLANATION);
            if (saved.review().content().contains(ReviewCategory.SUBMITTED_ANSWERS)) categories.add(Category.SUBMITTED_ANSWERS);
            if (saved.review().availability() == ReviewAvailability.AFTER_COMPLETION) categories.add(Category.REVIEW);
            // PASS_FAIL and a separate FEEDBACK payload have no saved practice disclosure rule.
            // Do not infer them from a score, explanation or the existence of grading facts.
            return new Policy(VERSION, binding, Family.PERSONAL_PRACTICE, categories);
        }

        /** R05's formal default only. It does not choose a manual/scheduled/immediate trigger. */
        public static Policy formalDefault(Binding binding) {
            return new Policy(VERSION, binding, Family.FORMAL_ASSESSMENT,
                    EnumSet.of(Category.TOTAL_SCORE, Category.PASS_FAIL));
        }
        @Override public String toString() { return "DisclosurePolicy[version=" + version + "]"; }
    }

    public record AttemptFacts(Binding binding, AttemptLifecycle.State state) {
        @Override public String toString() { return "DisclosureAttemptFacts"; }
    }

    /** PENDING has no invented result ID; FINAL binds the actual immutable result. */
    public record ResultFacts(int version, Binding binding, UUID resultId, ResultState state) {
        public static ResultFacts finalized(AutomaticResult result) {
            return new ResultFacts(result.version(), Binding.of(result), result.id(), ResultState.FINAL);
        }
        @Override public String toString() { return "DisclosureResultFacts[version=" + version + "]"; }
    }

    /** Projection of committed evidence owned by #556/#633/#634, not a persistence model. */
    public record ReleaseFacts(int version, Binding binding, UUID resultId, ReleaseState state) {
        @Override public String toString() { return "DisclosureReleaseFacts[version=" + version + "]"; }
    }

    /**
     * available only restricts authored/retained content; it can never authorize a category.
     * reviewAccess is the current saved-window/retention/revocation decision, independent of score
     * visibility. No clock, age limit, cache lifetime or permission is invented here.
     */
    public record Context(int version, Audience audience, Policy policy, AttemptFacts attempt,
                          ResultFacts result, ReleaseFacts release, ReviewAccess reviewAccess,
                          Set<Category> available) {
        public Context { available = immutableCategories(available); }
        @Override public String toString() { return "DisclosureContext[version=" + version + "]"; }
    }

    /** Safe internal classification: no score, answer, identity, content or storage references. */
    public record Decision(Outcome outcome, Reason reason) {
        public Decision {
            if (outcome == null || reason == null || (outcome == Outcome.ALLOW) != (reason == Reason.ALLOWED)
                    || (outcome == Outcome.PENDING) != (reason == Reason.RESULT_PENDING || reason == Reason.RELEASE_PENDING))
                throw new IllegalArgumentException("Invalid disclosure decision");
        }
    }

    public static final class Decisions {
        private final Map<Category, Decision> categories;
        private Decisions(Map<Category, Decision> categories) {
            this.categories = Collections.unmodifiableMap(new EnumMap<>(categories));
        }
        public Map<Category, Decision> categories() { return categories; }
        public Decision category(Category category) {
            return category == null ? denied(Reason.INVALID_INPUT) : categories.get(category);
        }
        public boolean allows(Category category) { return category(category).outcome() == Outcome.ALLOW; }

        /** Contract adapter for include flags: requests may narrow, never enlarge, server authority. */
        public Set<Category> requested(Set<Category> requested) {
            if (requested == null || requested.size() > Category.values().length
                    || requested.stream().anyMatch(Objects::isNull)) return Set.of();
            var selected = EnumSet.noneOf(Category.class);
            for (var category : requested) if (allows(category)) selected.add(category);
            return Collections.unmodifiableSet(selected);
        }
        @Override public String toString() { return "DisclosureDecisions"; }
    }

    public static Decisions evaluate(Context context) {
        Reason invalid = validate(context);
        var decisions = new EnumMap<Category, Decision>(Category.class);
        for (var category : Category.values()) {
            decisions.put(category, invalid == null ? decide(category, context) : denied(invalid));
        }
        return new Decisions(decisions);
    }

    /** Unknown wire names/versions never select a default category or enter diagnostic text. */
    public static Optional<Category> parseCategory(int version, String name) {
        if (version != VERSION || name == null) return Optional.empty();
        return Arrays.stream(Category.values()).filter(category -> category.name().equals(name)).findFirst();
    }

    private static Reason validate(Context context) {
        if (context == null || context.audience() == null) return Reason.ACCESS_DENIED;
        var audience = context.audience();
        // Do not inspect protected state or expose its validity before checking this invocation's access.
        if (audience.access() != Access.VERIFIED || audience.authenticatedUserId() == null
                || !audience.authenticatedUserId().equals(audience.boundUserId()) || audience.attemptId() == null)
            return Reason.ACCESS_DENIED;
        if (context.version() != VERSION) return Reason.UNSUPPORTED_VERSION;
        var policy = context.policy(); var attempt = context.attempt();
        var result = context.result(); var release = context.release();
        if (policy == null || attempt == null || result == null || release == null || policy.family() == null
                || policy.permitted() == null || attempt.state() == null || result.state() == null
                || release.state() == null || context.reviewAccess() == null || context.available() == null)
            return Reason.INVALID_INPUT;
        if (policy.version() != VERSION || result.version() != VERSION || release.version() != VERSION
                || attempt.state().version() != AttemptLifecycle.VERSION) return Reason.UNSUPPORTED_VERSION;
        var binding = policy.binding();
        if (binding == null || !binding.valid() || !binding.attemptId().equals(audience.attemptId())
                || !binding.equals(attempt.binding()) || !binding.equals(result.binding()) || !binding.equals(release.binding()))
            return Reason.INCONSISTENT_FACTS;
        if (attempt.state().execution() != AttemptLifecycle.Execution.SUBMITTED) {
            return result.state() == ResultState.FINAL || release.state() != ReleaseState.PENDING
                    || result.resultId() != null || release.resultId() != null
                    ? Reason.INCONSISTENT_FACTS : Reason.ATTEMPT_NOT_SUBMITTED;
        }
        if (result.state() == ResultState.PENDING) {
            if (result.resultId() != null || release.resultId() != null || release.state() != ReleaseState.PENDING)
                return Reason.INCONSISTENT_FACTS;
        } else if (result.resultId() == null || !result.resultId().equals(release.resultId())) {
            return Reason.INCONSISTENT_FACTS;
        }
        return null;
    }

    private static Decision decide(Category category, Context context) {
        if (!context.policy().permitted().contains(category)) return denied(Reason.POLICY_DENIED);
        if (context.result().state() == ResultState.PENDING) return pending(Reason.RESULT_PENDING);
        if (context.release().state() == ReleaseState.REVOKED) return denied(Reason.RELEASE_REVOKED);
        if (context.release().state() == ReleaseState.PENDING) return pending(Reason.RELEASE_PENDING);
        if (reviewCategory(category) && context.reviewAccess() != ReviewAccess.AVAILABLE)
            return denied(Reason.REVIEW_UNAVAILABLE);
        if (!context.available().contains(category)) return denied(Reason.CONTENT_UNAVAILABLE);
        return new Decision(Outcome.ALLOW, Reason.ALLOWED);
    }

    private static boolean reviewCategory(Category category) {
        return switch (category) {
            case TOTAL_SCORE, PASS_FAIL -> false;
            default -> true;
        };
    }
    private static Decision denied(Reason reason) { return new Decision(Outcome.DENY, reason); }
    private static Decision pending(Reason reason) { return new Decision(Outcome.PENDING, reason); }
    private static Set<Category> immutableCategories(Set<Category> values) {
        if (values == null) return null; // An incomplete fact is denied by evaluate, never defaulted.
        if (values.size() > Category.values().length || values.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid disclosure categories");
        var copy = EnumSet.noneOf(Category.class); copy.addAll(values);
        return Collections.unmodifiableSet(copy);
    }
}
