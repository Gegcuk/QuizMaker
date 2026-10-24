package uk.gegc.quizmaker.features.attempt.domain.model.policy;

import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValues.*;

/**
 * Transport-neutral v1 vocabulary (A01/A02, D01-D03, D07 and R01). Construction validates
 * the initial authenticated, flat, untimed practice slice; it never activates an engine.
 * Every dimension is explicit. There is no preset identity, caller identity or extension bag.
 * Content existence, exact per-question contract resolution, authorization and algorithm
 * availability must still be checked by the owning services before authoritative writes.
 * Presets/mode mapping, canonical wire bytes/hashes and persistence have separate owners.
 */
public record ResolvedAttemptPolicy(
        int schemaVersion,
        ContentPolicy content,
        SelectionPolicy questionSelection,
        QuestionOrderingPolicy questionOrdering,
        AnswerOrderingPolicy answerOrdering,
        DeliveryPolicy delivery,
        NavigationPolicy navigation,
        SubmissionPolicy submission,
        AnswerChangePolicy answerChange,
        DisclosurePolicy disclosure,
        HintPolicy hints,
        TimingPolicy timing,
        PausePolicy pause,
        AttemptLimitPolicy attempts,
        AccessPolicy access,
        GradingPolicy grading,
        ReviewPolicy review,
        ResultPolicy results,
        AccessibilityPolicy accessibility,
        IntegrityPolicy integrity
) {
    public static final int SCHEMA_VERSION = 1;
    public static final int BASIS_POINTS_SCALE = 10_000;
    public static final int DEFAULT_PASS_THRESHOLD_BASIS_POINTS = 7_000;

    public ResolvedAttemptPolicy {
        require(schemaVersion == SCHEMA_VERSION, UNSUPPORTED_SCHEMA_VERSION);
        present(content, questionSelection, questionOrdering, answerOrdering, delivery, navigation,
                submission, answerChange, disclosure, hints, timing, pause, attempts, access,
                grading, review, results, accessibility, integrity);
        // Reject the answer-oracle combination even before reporting the deferred capability.
        boolean feedbackAfterAcceptance = disclosure.rules().stream()
                .anyMatch(rule -> rule.when() == ReleaseMoment.AFTER_ACCEPTANCE);
        require(answerChange.mode() == AnswerChangeMode.LOCK_ON_ACCEPTANCE || !feedbackAfterAcceptance,
                INVALID_COMBINATION);
        require(answerChange.mode() == AnswerChangeMode.LOCK_ON_ACCEPTANCE, UNSUPPORTED_CAPABILITY);
        require(!submission.autosaveEnabled() || submission.draftsEnabled(), INVALID_COMBINATION);
        require(!submission.draftsEnabled() && !submission.autosaveEnabled(), UNSUPPORTED_CAPABILITY);
        require(!submission.requireAllAnswers() && submission.completionConfirmationRequired(),
                UNSUPPORTED_COMBINATION);
        for (DisclosureRule rule : disclosure.rules()) {
            ReleaseMoment expected = switch (rule.category()) {
                case CORRECTNESS, QUESTION_SCORE, CORRECT_ANSWER, EXPLANATION -> ReleaseMoment.AFTER_ACCEPTANCE;
                case TOTAL_SCORE -> ReleaseMoment.AFTER_COMPLETION;
                case RUBRIC, GRADER_COMMENTS, COHORT_STATISTICS -> ReleaseMoment.NEVER;
            };
            require(rule.when() == expected, UNSUPPORTED_COMBINATION);
        }
        require(review.content().size() == ReviewCategory.values().length, UNSUPPORTED_COMBINATION);
        require(results.runningProgressEnabled(), UNSUPPORTED_COMBINATION);
    }

    /** Exact symbolic names only: no case folding, aliases, ordinals or permissive fallback. */
    public static <E extends Enum<E>> E parseValue(Class<E> type, String value) {
        present(type, value);
        require(type.getDeclaringClass() == ResolvedAttemptPolicy.class, INVALID_VALUE);
        // String.equals checks length first; even oversized input never becomes exception text.
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(value)) return candidate;
        }
        throw new PolicyValidationException(UNKNOWN_VALUE);
    }

    /** The revision owns immutable question/response/scoring bindings (#597/#598), not this value. */
    public record ContentPolicy(UUID quizRevisionId, int revisionSchemaVersion, FormMode formMode,
                                SectionMode sections, CaseMode cases) {
        public ContentPolicy {
            present(quizRevisionId, formMode, sections, cases);
            require(revisionSchemaVersion == 1, UNSUPPORTED_CONTENT_VERSION);
        }
    }

    public record SelectionPolicy(SelectionMode mode, int algorithmVersion) {
        public SelectionPolicy { present(mode); strategyVersion(algorithmVersion); }
    }

    public record QuestionOrderingPolicy(QuestionOrder mode, int algorithmVersion) {
        public QuestionOrderingPolicy { present(mode); strategyVersion(algorithmVersion); }
    }

    /** SHUFFLED_PER_ATTEMPT is approved vocabulary; this record does not implement shuffling. */
    public record AnswerOrderingPolicy(AnswerOrder mode, int algorithmVersion) {
        public AnswerOrderingPolicy { present(mode); strategyVersion(algorithmVersion); }
    }

    public record DeliveryPolicy(DeliveryMode mode) {
        public DeliveryPolicy { present(mode); }
    }

    public record NavigationPolicy(NavigationMode mode) {
        public NavigationPolicy { present(mode); }
    }

    public record SubmissionPolicy(boolean draftsEnabled, boolean autosaveEnabled,
                                    boolean requireAllAnswers, boolean completionConfirmationRequired,
                                    SubmissionMode mode, RetrySemantics retries,
                                    FinalSubmitPayload finalSubmitPayload, UnansweredPolicy unanswered) {
        public SubmissionPolicy { present(mode, retries, finalSubmitPayload, unanswered); }
    }

    public record AnswerChangePolicy(AnswerChangeMode mode) {
        public AnswerChangePolicy { present(mode); }
    }

    public record DisclosureRule(DisclosureCategory category, ReleaseMoment when) {
        public DisclosureRule { present(category, when); }
    }

    /** Every category is required, including explicit NEVER for unavailable categories. */
    public record DisclosurePolicy(List<DisclosureRule> rules) {
        public DisclosurePolicy {
            rules = orderedValues(rules, DisclosureCategory.class, DisclosureRule::category, true);
        }

        public ReleaseMoment releaseOf(DisclosureCategory category) {
            present(category);
            return rules.get(category.ordinal()).when();
        }
    }

    public record HintPolicy(HintMode mode, HintSource source) {
        public HintPolicy { present(mode, source); }
    }

    /** No duration/default is invented for the untimed slice. Later timers require typed extensions. */
    public record TimingPolicy(TimingMode mode) {
        public TimingPolicy { present(mode); }
    }

    public record PausePolicy(PauseMode mode) {
        public PausePolicy { present(mode); }
    }

    public record AttemptLimitPolicy(AttemptLimitMode mode) {
        public AttemptLimitPolicy { present(mode); }
    }

    /** A validation requirement, never proof that a caller owns or may view a resource. */
    public record AccessPolicy(ParticipantMode participant, AccessValidation validation) {
        public AccessPolicy { present(participant, validation); }
    }

    /** Exact points and integer normalized units; calculation/rounding belongs to #603. */
    public record GradingPolicy(GradingMode mode, ScoreUnits units) {
        public GradingPolicy { present(mode, units); }
    }

    public record ReviewPolicy(ReviewAvailability availability, List<ReviewCategory> content, ReviewWindow window) {
        public ReviewPolicy {
            present(availability, window);
            content = orderedValues(content, ReviewCategory.class, value -> value, false);
        }
    }

    /** Threshold is explicit. D02's default constant is supplied by the future preset owner. */
    public record ResultPolicy(boolean runningProgressEnabled, int passThresholdBasisPoints,
                                ResultSelectionMode selection, LeaderboardMode leaderboard,
                                CertificateMode certificates) {
        public ResultPolicy {
            present(selection, leaderboard, certificates);
            require(passThresholdBasisPoints >= 0 && passThresholdBasisPoints <= BASIS_POINTS_SCALE, INVALID_VALUE);
        }
    }

    public record AccessibilityPolicy(AccommodationMode accommodations) {
        public AccessibilityPolicy { present(accommodations); }
    }

    public record IntegrityPolicy(FormEvidence formEvidence, EngineBinding engineBinding, VersionHandling versions) {
        public IntegrityPolicy { present(formEvidence, engineBinding, versions); }
    }

    // Only the selected v1 vocabulary is declared, rather than the future strategy catalogue.
    public enum FormMode { PRECOMPUTED }
    public enum SectionMode { IMPLICIT_FLAT }
    public enum CaseMode { NOT_CONFIGURED }
    public enum SelectionMode { ALL }
    public enum QuestionOrder { AUTHORED }
    public enum AnswerOrder { AUTHORED, SHUFFLED_PER_ATTEMPT }
    public enum DeliveryMode { ALL_AT_ONCE }
    public enum NavigationMode { FREE }
    public enum SubmissionMode { SINGLE_AND_ATOMIC_BATCH }
    public enum RetrySemantics { IDENTICAL_REPLAY_CHANGED_CONFLICT }
    public enum FinalSubmitPayload { NONE }
    public enum UnansweredPolicy { NO_CREDIT_WITHOUT_ACCEPTANCE }
    public enum AnswerChangeMode { LOCK_ON_ACCEPTANCE, EDIT_UNTIL_FINALIZATION }
    public enum DisclosureCategory {
        CORRECTNESS, QUESTION_SCORE, CORRECT_ANSWER, EXPLANATION, TOTAL_SCORE, RUBRIC, GRADER_COMMENTS, COHORT_STATISTICS
    }
    public enum ReleaseMoment { NEVER, AFTER_ACCEPTANCE, AFTER_COMPLETION }
    public enum HintMode { DISABLED, ON_REQUEST }
    public enum HintSource { AUTHORED }
    public enum TimingMode { NONE }
    public enum PauseMode { DISABLED }
    public enum AttemptLimitMode { UNLIMITED }
    public enum ParticipantMode { AUTHENTICATED }
    public enum AccessValidation { SERVER_REVALIDATED_AT_START_AND_READ }
    public enum GradingMode { BINARY }
    public enum ScoreUnits { EXACT_POINTS_WITH_INTEGER_BASIS_POINTS }
    public enum ReviewAvailability { AFTER_COMPLETION }
    public enum ReviewCategory { SUBMITTED_ANSWERS, CORRECTNESS, CORRECT_ANSWERS, EXPLANATIONS }
    public enum ReviewWindow { WHILE_ACCOUNT_AND_RESULT_RETAINED }
    public enum ResultSelectionMode { NOT_CONFIGURED }
    public enum LeaderboardMode { DISABLED }
    public enum CertificateMode { DISABLED }
    public enum AccommodationMode { NOT_CONFIGURED }
    public enum FormEvidence { EXACT_PERSISTED_IDENTITIES_AND_ORDER }
    public enum EngineBinding { IMMUTABLE }
    public enum VersionHandling { REJECT_UNKNOWN }
}
